package com.technexus.auth.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialTest {
	private static final String HASH = "$argon2id$" + "a".repeat(32);

	@Test
	void tracksFailuresAndPasswordReplacement() {
		var credential = new Credential(UUID.randomUUID(), HASH);
		credential.recordFailure();
		credential.recordFailure();
		assertEquals(2, credential.failedAttempts());
		credential.replacePassword("$argon2id$" + "b".repeat(32));
		assertEquals(0, credential.failedAttempts());
		credential.disable();
		assertEquals(CredentialStatus.DISABLED, credential.status());
		assertThrows(DomainException.class, credential::recordFailure);
		assertThrows(DomainException.class, () -> credential.replacePassword(HASH));
	}

	@Test
	void rejectsNonArgonAndShortHashes() {
		assertThrows(DomainException.class, () -> new Credential(UUID.randomUUID(), "a".repeat(40)));
		assertThrows(DomainException.class, () -> new Credential(UUID.randomUUID(), "$argon2id$short"));
	}
}
