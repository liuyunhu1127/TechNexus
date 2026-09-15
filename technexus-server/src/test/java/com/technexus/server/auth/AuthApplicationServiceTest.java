package com.technexus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import com.technexus.server.auth.infrastructure.InMemoryAuthAccountStore;
import com.technexus.server.security.BearerTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class AuthApplicationServiceTest {
	private final InMemoryAuthAccountStore store = new InMemoryAuthAccountStore();
	private final BearerTokenService tokens = new BearerTokenService(new ObjectMapper(),
			"test-secret-that-is-at-least-thirty-two-bytes-long");
	private final AuthApplicationService auth = new AuthApplicationService(store,
			Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(), tokens, new InMemoryLoginRateLimiter(),
			"admin@example.com");

	@Test
	void registrationLoginAndSingleUseRefreshFormAWorkingSessionFlow() {
		var account = auth.register("ADMIN@EXAMPLE.COM", "correct-horse-battery-staple", "管理员");
		assertEquals("admin@example.com", account.email());
		assertEquals(true, account.administrator());

		var first = auth.login("admin@example.com", "correct-horse-battery-staple", "127.0.0.1");
		var second = auth.refresh(first.refreshToken());

		assertNotNull(first.accessToken());
		assertNotNull(second.accessToken());
		var reused = assertThrows(DomainException.class, () -> auth.refresh(first.refreshToken()));
		assertEquals("AUTH_REFRESH_REUSED", reused.code());
	}

	@Test
	void rejectsDuplicateRegistrationAndInvalidPassword() {
		auth.register("user@example.com", "correct-horse-battery-staple", "用户");
		assertEquals("EMAIL_EXISTS", assertThrows(DomainException.class,
				() -> auth.register("user@example.com", "another-secure-password", "用户")).code());
		assertEquals("AUTH_INVALID",
				assertThrows(DomainException.class, () -> auth.login("user@example.com", "wrong-password", "127.0.0.1"))
						.code());
	}

	@Test
	void locksAccountAfterFiveFailures() {
		auth.register("locked@example.com", "correct-horse-battery-staple", "用户");
		for (var attempt = 0; attempt < 5; attempt++) {
			assertThrows(DomainException.class, () -> auth.login("locked@example.com", "wrong-password", "127.0.0.2"));
		}

		assertEquals("AUTH_INVALID", assertThrows(DomainException.class,
				() -> auth.login("locked@example.com", "correct-horse-battery-staple", "127.0.0.2")).code());
	}

	@Test
	void logoutInvalidatesAccessSession() {
		var account = auth.register("session@example.com", "correct-horse-battery-staple", "用户");
		var pair = auth.login("session@example.com", "correct-horse-battery-staple", "127.0.0.3");
		var claims = tokens.verify(pair.accessToken());

		auth.logout(pair.refreshToken());

		assertFalse(store.isAccessSessionActive(account.publicId(), claims.sid(), claims.sessionVersion()));
	}
}
