package com.technexus.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthSessionTest {
	@Test
	void refreshTokenCanOnlyRotateOnce() {
		var now = Instant.parse("2026-09-14T12:00:00Z");
		var session = new AuthSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(32),
				now.plusSeconds(60));
		var next = session.rotate(UUID.randomUUID(), "b".repeat(32), now.plusSeconds(120), now);
		assertEquals(SessionStatus.ROTATED, session.status());
		assertEquals(SessionStatus.ACTIVE, next.status());
		assertThrows(DomainException.class,
				() -> session.rotate(UUID.randomUUID(), "c".repeat(32), now.plusSeconds(120), now));
	}

	@Test
	void expiredSessionCannotBeUsed() {
		var session = new AuthSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(32),
				Instant.EPOCH);
		assertThrows(DomainException.class, () -> session.requireUsable(Instant.now()));
		assertEquals(SessionStatus.EXPIRED, session.status());
	}

	@Test
	void validatesHashAndRevokeIsIdempotent() {
		var now = Instant.now();
		assertThrows(DomainException.class, () -> new AuthSession(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "short", now.plusSeconds(10)));
		var session = new AuthSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(32),
				now.plusSeconds(10));
		session.requireUsable(now);
		session.revoke();
		assertEquals(1, session.version());
		session.revoke();
		assertEquals(1, session.version());
		assertThrows(DomainException.class, () -> session.requireUsable(now));
	}
}
