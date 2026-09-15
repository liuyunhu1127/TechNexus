package com.technexus.server.user.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.server.user.infrastructure.InMemoryUserRepository;
import com.technexus.user.domain.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileApplicationServiceTest {
	@Test
	void updatesProfileWithOptimisticVersion() {
		var repository = new InMemoryUserRepository();
		var user = User.restore(UUID.randomUUID(), "user@example.com", "Before", "",
				com.technexus.user.domain.UserStatus.ACTIVE, 0, 0);
		repository.save(user);
		var profiles = new ProfileApplicationService(repository);

		var updated = profiles.update(user.publicId(), 0, "After", "Bio");

		assertEquals("After", updated.displayName());
		assertEquals(1, updated.version());
		assertEquals("VERSION_CONFLICT",
				assertThrows(DomainException.class, () -> profiles.update(user.publicId(), 0, "Again", null)).code());
	}
}
