package com.technexus.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CookieMutationGuardFilter extends OncePerRequestFilter {
	private static final Set<String> GUARDED = Set.of("/api/v1/auth/refresh", "/api/v1/auth/logout");
	private final Set<String> allowedOrigins;

	public CookieMutationGuardFilter(
			@Value("${technexus.security.allowed-origins:http://localhost:3000}") String origins) {
		this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(value -> !value.isEmpty())
				.collect(Collectors.toUnmodifiableSet());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!"POST".equals(request.getMethod()) || !GUARDED.contains(request.getRequestURI())) {
			chain.doFilter(request, response);
			return;
		}
		var origin = request.getHeader("Origin");
		var headerToken = request.getHeader("X-CSRF-Token");
		var cookieToken = cookie(request, "tn_csrf");
		if (!allowedOrigins.contains(origin) || !constantTimeEquals(headerToken, cookieToken)) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			response.setContentType("application/problem+json;charset=UTF-8");
			response.getWriter().write(
					"{\"type\":\"about:blank\",\"title\":\"请求来源或 CSRF 令牌无效\",\"status\":403,\"code\":\"CSRF_INVALID\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	private static String cookie(HttpServletRequest request, String name) {
		if (request.getCookies() == null)
			return null;
		return Arrays.stream(request.getCookies()).filter(value -> name.equals(value.getName())).map(Cookie::getValue)
				.findFirst().orElse(null);
	}

	private static boolean constantTimeEquals(String left, String right) {
		if (left == null || right == null)
			return false;
		return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
	}
}
