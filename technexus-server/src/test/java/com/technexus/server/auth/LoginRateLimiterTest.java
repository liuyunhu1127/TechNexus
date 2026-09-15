package com.technexus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {
	@Test
	void rejectsEleventhAttemptInWindow() {
		var limiter = new InMemoryLoginRateLimiter();
		for (var attempt = 0; attempt < 10; attempt++)
			limiter.acquire("user@example.com", "127.0.0.1");

		var error = assertThrows(DomainException.class, () -> limiter.acquire("user@example.com", "127.0.0.1"));

		assertEquals("AUTH_RATE_LIMITED", error.code());
	}
}
