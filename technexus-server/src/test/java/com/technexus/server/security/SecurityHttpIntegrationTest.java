package com.technexus.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "technexus.security.jwt-secret=integration-secret-that-is-at-least-thirty-two-bytes-long")
@ActiveProfiles("test")
@Import(SecurityHttpIntegrationTest.FailureEndpoint.class)
class SecurityHttpIntegrationTest {
	@Autowired
	TestRestTemplate http;

	@Test
	void missingCsrfIsForbiddenAndRegularUserGetsForbiddenForAdminApi() {
		var csrfFailure = http.postForEntity("/api/v1/auth/refresh", null, Map.class);
		assertEquals(HttpStatus.FORBIDDEN, csrfFailure.getStatusCode());
		assertEquals("CSRF_INVALID", csrfFailure.getBody().get("code"));

		var email = "security-" + UUID.randomUUID() + "@technexus.local";
		var password = "Security-Test!2026";
		http.postForEntity("/api/v1/auth/register",
				Map.of("email", email, "password", password, "displayName", "Security Test"), Map.class);
		var login = http.postForEntity("/api/v1/auth/login", Map.of("account", email, "password", password), Map.class);
		var headers = new HttpHeaders();
		headers.setBearerAuth((String) login.getBody().get("accessToken"));
		var denied = http.exchange("/api/v1/admin/dashboard", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

		assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
		assertEquals("ACCESS_DENIED", denied.getBody().get("code"));

		var failed = http.exchange("/api/v1/test/internal-error", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
	}

	@RestController
	static class FailureEndpoint {
		@GetMapping("/api/v1/test/internal-error")
		void fail() {
			throw new IllegalStateException("synthetic test failure");
		}
	}
}
