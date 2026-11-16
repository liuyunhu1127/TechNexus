package com.technexus.server.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
	public static final String HEADER = "X-Request-Id";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");
	private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var started = System.nanoTime();
		var requestId = correlationId(request.getHeader(HEADER));
		MDC.put("requestId", requestId);
		MDC.put("traceId", requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		} finally {
			var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
			log.info("http_request_completed method={} path={} status={} durationMs={}", request.getMethod(),
					request.getRequestURI(), response.getStatus(), elapsedMillis);
			MDC.remove("traceId");
			MDC.remove("requestId");
		}
	}

	static String correlationId(String requested) {
		if (requested != null && SAFE_ID.matcher(requested).matches())
			return requested;
		return UUID.randomUUID().toString();
	}
}
