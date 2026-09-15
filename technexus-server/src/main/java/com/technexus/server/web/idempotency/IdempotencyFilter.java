package com.technexus.server.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Component
@Profile("!test")
public class IdempotencyFilter extends OncePerRequestFilter {
	private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
	private static final Set<String> EXCLUDED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh");
	private final IdempotencyStore store;
	private final ObjectMapper json;

	public IdempotencyFilter(IdempotencyStore store, ObjectMapper json) {
		this.store = store;
		this.json = json;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/v1/") || !METHODS.contains(request.getMethod())
				|| EXCLUDED_PATHS.contains(request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var key = request.getHeader("Idempotency-Key");
		if (key == null || key.length() < 8 || key.length() > 128) {
			problem(response, 400, "IDEMPOTENCY_KEY_INVALID", "Idempotency-Key 长度必须为 8 到 128 个字符");
			return;
		}

		var bufferedRequest = new BufferedRequest(request);
		var reservation = store.reserve(actor(), key, request.getMethod(), request.getRequestURI(),
				hash(request, bufferedRequest.body()));
		switch (reservation.state()) {
			case CONFLICT -> problem(response, 409, "IDEMPOTENCY_KEY_REUSED", "同一幂等键不能用于不同请求");
			case IN_PROGRESS -> problem(response, 409, "IDEMPOTENCY_REQUEST_IN_PROGRESS", "同一请求仍在处理中");
			case REPLAY -> replay(response, reservation);
			case ACQUIRED -> execute(bufferedRequest, response, chain, reservation.id());
		}
	}

	private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain, long id)
			throws IOException, ServletException {
		var cachedResponse = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(request, cachedResponse);
			var body = new String(cachedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
			if (cachedResponse.getStatus() >= 500)
				store.abandon(id);
			else
				store.complete(id, cachedResponse.getStatus(), body);
			cachedResponse.copyBodyToResponse();
		} catch (IOException | ServletException | RuntimeException error) {
			store.abandon(id);
			throw error;
		}
	}

	private void replay(HttpServletResponse response, IdempotencyStore.Reservation reservation) throws IOException {
		response.setStatus(reservation.responseStatus());
		response.setHeader("X-Idempotent-Replay", "true");
		if (reservation.responseBody() != null) {
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write(reservation.responseBody());
		}
	}

	private void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		json.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", "Request rejected", "status",
				status, "detail", detail, "code", code));
	}

	private static UUID actor() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated())
			return null;
		try {
			return UUID.fromString(authentication.getName());
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static byte[] hash(HttpServletRequest request, byte[] body) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			if (request.getQueryString() != null)
				digest.update(request.getQueryString().getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			return digest.digest(body);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static final class BufferedRequest extends HttpServletRequestWrapper {
		private final byte[] body;

		private BufferedRequest(HttpServletRequest request) throws IOException {
			super(request);
			this.body = request.getInputStream().readAllBytes();
		}

		private byte[] body() {
			return body;
		}

		@Override
		public ServletInputStream getInputStream() {
			var input = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return input.read();
				}

				@Override
				public boolean isFinished() {
					return input.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException("Async request bodies are not supported");
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			var charset = getCharacterEncoding() == null
					? StandardCharsets.UTF_8
					: java.nio.charset.Charset.forName(getCharacterEncoding());
			return new BufferedReader(new InputStreamReader(getInputStream(), charset));
		}
	}
}
