package com.technexus.server.auth.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthAccountStore {
	StoredAccount createAccount(UUID publicId, String normalizedEmail, String passwordHash, String displayName);
	Optional<StoredAccount> findByEmail(String normalizedEmail);
	void createSession(StoredAccount account, UUID sid, UUID tokenFamily, byte[] refreshHash, Instant expiresAt);
	Rotation rotateSession(byte[] currentRefreshHash, UUID newSid, byte[] newRefreshHash, Instant newExpiresAt);
	void revokeSession(byte[] refreshHash);
	boolean isAccessSessionActive(UUID userId, UUID sid, long sessionVersion);
	boolean isLoginLocked(String normalizedEmail, Instant now);
	void recordLoginFailure(String normalizedEmail, Instant now, int threshold, Instant lockedUntil);
	void recordLoginSuccess(String normalizedEmail);

	record StoredAccount(UUID publicId, String normalizedEmail, String passwordHash, String displayName,
			long sessionVersion) {
	}

	record Rotation(RotationStatus status, StoredAccount account, UUID tokenFamily) {
		public static Rotation invalid(RotationStatus status) {
			return new Rotation(status, null, null);
		}
	}

	enum RotationStatus {
		ROTATED, INVALID, EXPIRED, REUSED
	}
}
