package com.technexus.server.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private static final String REFRESH_COOKIE = "tn_refresh";
	private final AuthApplicationService auth;

	public AuthController(AuthApplicationService auth) {
		this.auth = auth;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
		var account = auth.register(request.email(), request.password(), request.displayName());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", Map.of("id", account.publicId(), "status",
				"ACTIVE", "displayName", account.displayName(), "bio", "", "version", account.sessionVersion())));
	}

	@PostMapping("/login")
	ResponseEntity<?> login(HttpServletRequest servletRequest, @Valid @RequestBody LoginRequest request) {
		return tokenResponse(auth.login(request.account(), request.password(), servletRequest.getRemoteAddr()));
	}

	@PostMapping("/refresh")
	ResponseEntity<?> refresh(HttpServletRequest request) {
		return tokenResponse(auth.refresh(cookie(request, REFRESH_COOKIE)));
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	ResponseEntity<?> logout(HttpServletRequest request) {
		auth.logout(cookieOrNull(request, REFRESH_COOKIE));
		var expired = ResponseCookie.from(REFRESH_COOKIE, "").httpOnly(true).secure(true).sameSite("Strict")
				.path("/api/v1/auth").maxAge(Duration.ZERO).build();
		var expiredCsrf = ResponseCookie.from("tn_csrf", "").httpOnly(false).secure(true).sameSite("Strict")
				.path("/api/v1/auth").maxAge(Duration.ZERO).build();
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString(), expiredCsrf.toString())
				.build();
	}

	private static ResponseEntity<?> tokenResponse(AuthApplicationService.TokenPair pair) {
		var csrf = UUID.randomUUID().toString();
		var cookie = ResponseCookie.from(REFRESH_COOKIE, pair.refreshToken()).httpOnly(true).secure(true)
				.sameSite("Strict").path("/api/v1/auth").maxAge(Duration.ofDays(30)).build();
		var csrfCookie = ResponseCookie.from("tn_csrf", csrf).httpOnly(false).secure(true).sameSite("Strict")
				.path("/api/v1/auth").maxAge(Duration.ofDays(30)).build();
		return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString(), csrfCookie.toString())
				.body(Map.of("accessToken", pair.accessToken(), "tokenType", "Bearer", "expiresIn", pair.expiresIn(),
						"csrfToken", csrf));
	}

	private static String cookie(HttpServletRequest request, String name) {
		var value = cookieOrNull(request, name);
		if (value == null)
			throw new com.technexus.common.domain.DomainException("REFRESH_REQUIRED", "缺少刷新令牌");
		return value;
	}

	private static String cookieOrNull(HttpServletRequest request, String name) {
		if (request.getCookies() == null)
			return null;
		return Arrays.stream(request.getCookies()).filter(item -> name.equals(item.getName())).map(Cookie::getValue)
				.findFirst().orElse(null);
	}

	record RegisterRequest(@Email String email, @Size(min = 12, max = 128) String password,
			@Size(min = 1, max = 80) String displayName) {
	}
	record LoginRequest(@NotBlank @Size(min = 3, max = 254) String account,
			@NotBlank @Size(max = 128) String password) {
	}
}
