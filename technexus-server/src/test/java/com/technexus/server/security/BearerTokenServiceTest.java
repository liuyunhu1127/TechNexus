package com.technexus.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BearerTokenServiceTest {
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC);
	private final BearerTokenService tokens = new BearerTokenService(new ObjectMapper(),
			"a-secure-test-secret-that-is-long-enough", clock);

	@Test
	void roundTripsSignedClaims() {
		var userId = UUID.randomUUID();
		var sid = UUID.randomUUID();
		var claims = tokens.verify(tokens.issue(userId, sid, 3, true));
		assertEquals(userId, claims.userId());
		assertEquals(sid, claims.sid());
		assertEquals(3, claims.sessionVersion());
		assertEquals("user admin operations", claims.scope());
	}

	@Test
	void rejectsTamperedSignature() {
		var token = tokens.issue(UUID.randomUUID(), UUID.randomUUID(), 0, false);
		var tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
		assertThrows(DomainException.class, () -> tokens.verify(tampered));
	}
}
