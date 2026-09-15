package com.technexus.user.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {
	@Test
	void cancellationIsTerminalAndRevokesSessions() {
		var user = new User(UUID.randomUUID(), "Owner@Example.com", "Owner");
		user.activate();
		user.cancel();
		assertEquals(UserStatus.CANCELLED, user.status());
		assertEquals(1, user.sessionVersion());
		assertThrows(DomainException.class, () -> user.updateProfile("Changed"));
	}

	@Test
	void lockingRevokesAllSessions() {
		var user = new User(UUID.randomUUID(), "a@example.com", "A");
		user.activate();
		user.lock();
		assertEquals(UserStatus.LOCKED, user.status());
		assertEquals(1, user.sessionVersion());
	}

	@Test
	void validatesIdentityProfileAndTransitions() {
		assertThrows(DomainException.class, () -> new User(UUID.randomUUID(), "invalid", "Name"));
		assertThrows(DomainException.class, () -> new User(UUID.randomUUID(), "a@example.com", " "));
		var user = new User(UUID.randomUUID(), " A@Example.com ", " Name ");
		assertEquals("a@example.com", user.normalizedEmail());
		assertEquals("Name", user.displayName());
		assertEquals(UserStatus.REGISTERED, user.status());
		user.activate();
		assertFalse(user.pullDomainEvents().isEmpty());
		assertThrows(DomainException.class, user::activate);
		user.updateProfile(" Next ", " bio ");
		assertEquals("Next", user.displayName());
		assertEquals("bio", user.bio());
		assertThrows(DomainException.class, () -> user.updateProfile("ok", "x".repeat(1001)));
		user.disable();
		assertEquals(UserStatus.DISABLED, user.status());
		assertEquals(1, user.sessionVersion());
	}

	@Test
	void restoresAndAllowsLockedUserToReactivate() {
		var id = UUID.randomUUID();
		var user = User.restore(id, "x@example.com", "X", null, UserStatus.LOCKED, 4, 9);
		assertEquals(9, user.version());
		assertEquals("", user.bio());
		user.activate();
		assertEquals(UserStatus.ACTIVE, user.status());
		assertTrue(user.version() > 9);
	}
}
