package com.technexus.server.auth.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.auth.application.port.AuthAccountStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryAuthAccountStore implements AuthAccountStore {
	private final Map<String, StoredAccount> accounts = new ConcurrentHashMap<>();
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final Map<String, LoginFailure> failures = new ConcurrentHashMap<>();
	private final Clock clock = Clock.systemUTC();

	@Override
	public StoredAccount createAccount(UUID publicId, String email, String hash, String displayName) {
		var account = new StoredAccount(publicId, email, hash, displayName, 0);
		if (accounts.putIfAbsent(email, account) != null)
			throw new DomainException("EMAIL_EXISTS", "邮箱已注册");
		return account;
	}

	@Override
	public Optional<StoredAccount> findByEmail(String normalizedEmail) {
		return Optional.ofNullable(accounts.get(normalizedEmail));
	}

	@Override
	public void createSession(StoredAccount account, UUID sid, UUID family, byte[] hash, Instant expiresAt) {
		sessions.put(key(hash), new Session(account, sid, family, expiresAt, State.ACTIVE));
	}

	@Override
	public synchronized Rotation rotateSession(byte[] currentHash, UUID newSid, byte[] newHash, Instant newExpiresAt) {
		var currentKey = key(currentHash);
		var session = sessions.get(currentKey);
		if (session == null)
			return Rotation.invalid(RotationStatus.INVALID);
		if (session.state == State.ROTATED) {
			sessions.replaceAll(
					(ignored, item) -> item.family.equals(session.family) ? item.withState(State.REVOKED) : item);
			return Rotation.invalid(RotationStatus.REUSED);
		}
		if (session.state != State.ACTIVE)
			return Rotation.invalid(RotationStatus.INVALID);
		if (!session.expiresAt.isAfter(clock.instant()))
			return Rotation.invalid(RotationStatus.EXPIRED);
		sessions.put(currentKey, session.withState(State.ROTATED));
		sessions.put(key(newHash), new Session(session.account, newSid, session.family, newExpiresAt, State.ACTIVE));
		return new Rotation(RotationStatus.ROTATED, session.account, session.family);
	}

	@Override
	public void revokeSession(byte[] refreshHash) {
		sessions.computeIfPresent(key(refreshHash), (ignored, session) -> session.withState(State.REVOKED));
	}

	@Override
	public boolean isAccessSessionActive(UUID userId, UUID sid, long sessionVersion) {
		return sessions.values().stream()
				.anyMatch(session -> session.account.publicId().equals(userId) && session.sid.equals(sid)
						&& session.account.sessionVersion() == sessionVersion && session.state == State.ACTIVE
						&& session.expiresAt.isAfter(clock.instant()));
	}

	@Override
	public boolean isLoginLocked(String normalizedEmail, Instant now) {
		var failure = failures.get(normalizedEmail);
		return failure != null && failure.lockedUntil != null && failure.lockedUntil.isAfter(now);
	}

	@Override
	public void recordLoginFailure(String normalizedEmail, Instant now, int threshold, Instant lockedUntil) {
		failures.compute(normalizedEmail, (ignored, current) -> {
			var attempts = current == null ? 1 : current.attempts + 1;
			return new LoginFailure(attempts, attempts >= threshold ? lockedUntil : null);
		});
	}

	@Override
	public void recordLoginSuccess(String normalizedEmail) {
		failures.remove(normalizedEmail);
	}

	private static String key(byte[] hash) {
		return Base64.getEncoder().encodeToString(hash);
	}
	private enum State {
		ACTIVE, ROTATED, REVOKED
	}
	private record Session(StoredAccount account, UUID sid, UUID family, Instant expiresAt, State state) {
		Session withState(State value) {
			return new Session(account, sid, family, expiresAt, value);
		}
	}
	private record LoginFailure(int attempts, Instant lockedUntil) {
	}
}
