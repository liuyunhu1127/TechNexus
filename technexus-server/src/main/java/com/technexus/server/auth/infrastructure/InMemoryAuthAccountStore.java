package com.technexus.server.auth.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.auth.application.port.AuthAccountStore;
import com.technexus.server.user.infrastructure.InMemoryUserRepository;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryAuthAccountStore implements AuthAccountStore {
	private final Map<UUID, String> passwordHashes = new ConcurrentHashMap<>();
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final Map<String, LoginFailure> failures = new ConcurrentHashMap<>();
	private final Clock clock = Clock.systemUTC();
	private final UserRepository users;

	public InMemoryAuthAccountStore() {
		this(new InMemoryUserRepository());
	}

	@Autowired
	public InMemoryAuthAccountStore(UserRepository users) {
		this.users = users;
	}

	@Override
	public synchronized StoredAccount createAccount(UUID publicId, String email, String hash, String displayName) {
		var account = new StoredAccount(publicId, email, hash, displayName, 0);
		if (users.findByEmail(email).isPresent())
			throw new DomainException("EMAIL_EXISTS", "邮箱已注册");
		passwordHashes.put(publicId, hash);
		users.save(User.restore(publicId, email, displayName, "", UserStatus.ACTIVE, 0, 0));
		return account;
	}

	@Override
	public Optional<StoredAccount> findByEmail(String normalizedEmail) {
		return users.findByEmail(normalizedEmail).filter(user -> user.status() == UserStatus.ACTIVE)
				.flatMap(user -> Optional.ofNullable(passwordHashes.get(user.publicId()))
						.map(hash -> new StoredAccount(user.publicId(), user.normalizedEmail(), hash,
								user.displayName(), user.sessionVersion())));
	}

	@Override
	public synchronized void createSession(StoredAccount account, UUID sid, UUID family, byte[] hash,
			Instant expiresAt) {
		if (!activeAccountVersion(account.publicId(), account.sessionVersion()))
			throw new DomainException("AUTH_INVALID", "账号状态已变化，请重新登录");
		sessions.put(key(hash), new Session(account, sid, family, expiresAt, State.ACTIVE));
	}

	@Override
	public synchronized Rotation rotateSession(byte[] currentHash, UUID newSid, byte[] newHash, Instant newExpiresAt) {
		var currentKey = key(currentHash);
		var session = sessions.get(currentKey);
		if (session == null || !activeAccountVersion(session.account.publicId(), session.account.sessionVersion()))
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
	public synchronized void revokeAllSessions(UUID userId) {
		sessions.replaceAll((ignored,
				session) -> session.account.publicId().equals(userId) ? session.withState(State.REVOKED) : session);
	}

	@Override
	public void updatePassword(UUID userId, String passwordHash) {
		if (passwordHashes.replace(userId, passwordHash) == null)
			throw new DomainException("ACCOUNT_ACTION_INVALID", "验证已失效，请重新申请");
		users.findById(userId).ifPresent(user -> failures.remove(user.normalizedEmail()));
	}

	@Override
	public boolean isAccessSessionActive(UUID userId, UUID sid, long sessionVersion) {
		return activeAccountVersion(userId, sessionVersion) && sessions.values().stream()
				.anyMatch(session -> session.account.publicId().equals(userId) && session.sid.equals(sid)
						&& session.account.sessionVersion() == sessionVersion && session.state == State.ACTIVE
						&& session.expiresAt.isAfter(clock.instant()));
	}

	private boolean activeAccountVersion(UUID userId, long sessionVersion) {
		return users.findById(userId)
				.filter(user -> user.status() == UserStatus.ACTIVE && user.sessionVersion() == sessionVersion)
				.isPresent();
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
