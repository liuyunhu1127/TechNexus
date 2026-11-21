package com.technexus.server.user.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailActionStore {
	void replace(Action action);
	Optional<Action> lock(UUID id, Kind kind);
	boolean consume(UUID id);

	enum Kind {
		VERIFY_EMAIL, CHANGE_EMAIL, RESET_PASSWORD
	}

	record Action(UUID id, UUID userId, Kind kind, String currentEmail, String targetEmail, long sessionVersion,
			byte[] firstDigest, byte[] secondDigest, Instant expiresAt) {
	}
}
