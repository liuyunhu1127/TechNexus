package com.technexus.server.web;

import com.technexus.common.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(DomainException.class)
	ProblemDetail domain(DomainException error, HttpServletRequest request) {
		var status = switch (error.code()) {
			case "RESOURCE_NOT_FOUND", "CONFIG_NOT_FOUND" -> HttpStatus.NOT_FOUND;
			case "AUTH_REQUIRED", "AUTH_INVALID", "REFRESH_INVALID", "REFRESH_EXPIRED", "AUTH_REFRESH_REUSED",
					"TOKEN_INVALID", "TOKEN_EXPIRED" ->
				HttpStatus.UNAUTHORIZED;
			case "CONTENT_FORBIDDEN", "DEMAND_FORBIDDEN", "PROPOSAL_FORBIDDEN", "COMMENT_FORBIDDEN" ->
				HttpStatus.FORBIDDEN;
			case "EMAIL_EXISTS", "VERSION_CONFLICT" -> HttpStatus.CONFLICT;
			case "AUTH_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
			default -> HttpStatus.BAD_REQUEST;
		};
		return problem(status, error.code(), error.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail validation(MethodArgumentNotValidException error, HttpServletRequest request) {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验失败", request);
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
		var result = ProblemDetail.forStatusAndDetail(status, detail);
		result.setTitle(code);
		result.setType(URI.create("https://technexus.local/problems/" + code.toLowerCase().replace('_', '-')));
		result.setInstance(URI.create(request.getRequestURI()));
		result.setProperty("code", code);
		return result;
	}
}
