package com.technexus.user.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoleTest {
	@Test
	void grantsAndRevokesNormalizedPermissionsIdempotently() {
		var role = new Role("admin", List.of("content.read"));
		assertEquals(1, role.version());
		role.grant("content.read");
		assertEquals(1, role.version());
		role.grant("content.write");
		role.revoke("missing.permission");
		role.revoke("content.read");
		assertEquals(3, role.version());
		assertTrue(role.permissions().contains("content.write"));
	}

	@Test
	void rejectsInvalidCodes() {
		assertThrows(DomainException.class, () -> new Role(null, List.of()));
		assertThrows(DomainException.class, () -> new Role("A", List.of()));
		assertThrows(DomainException.class, () -> new Role("ok", List.of("bad space")));
	}
}
