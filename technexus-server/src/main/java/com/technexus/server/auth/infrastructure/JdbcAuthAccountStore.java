package com.technexus.server.auth.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.auth.application.port.AuthAccountStore;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcAuthAccountStore implements AuthAccountStore {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcAuthAccountStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Override
	public StoredAccount createAccount(UUID publicId, String email, String passwordHash, String displayName) {
		try {
			return transactions.execute(status -> {
				var keys = new GeneratedKeyHolder();
				jdbc.update(connection -> {
					PreparedStatement statement = connection.prepareStatement(
							"""
									INSERT INTO tn_user(public_id,email_normalized,display_name,status,session_version,version,created_at,updated_at)
									VALUES (?,?,?,'ACTIVE',0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
									""",
							Statement.RETURN_GENERATED_KEYS);
					statement.setBytes(1, UuidBinary.toBytes(publicId));
					statement.setString(2, email);
					statement.setString(3, displayName);
					return statement;
				}, keys);
				var userId = keys.getKey().longValue();
				jdbc.update(
						"""
								INSERT INTO tn_auth_credential(user_id,password_hash,status,failed_attempt,version,created_at,updated_at)
								VALUES (?,?,'ACTIVE',0,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
								""",
						userId, passwordHash);
				return new StoredAccount(publicId, email, passwordHash, displayName, 0);
			});
		} catch (DuplicateKeyException error) {
			throw new DomainException("EMAIL_EXISTS", "邮箱已注册");
		}
	}

	@Override
	public Optional<StoredAccount> findByEmail(String email) {
		return jdbc.query("""
				SELECT u.public_id,u.email_normalized,c.password_hash,u.display_name,u.session_version
				FROM tn_user u JOIN tn_auth_credential c ON c.user_id=u.id
				WHERE u.email_normalized=? AND u.status='ACTIVE' AND c.status='ACTIVE'
				""", (result, row) -> account(result.getBytes("public_id"), result.getString("email_normalized"),
				result.getString("password_hash"), result.getString("display_name"), result.getLong("session_version")),
				email).stream().findFirst();
	}

	@Override
	public void createSession(StoredAccount account, UUID sid, UUID family, byte[] hash, Instant expiresAt) {
		jdbc.update(
				"""
						INSERT INTO tn_auth_session(sid,user_id,token_family,refresh_hash,status,expires_at,created_at,updated_at)
						SELECT ?,u.id,?,?,'ACTIVE',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user u WHERE u.public_id=?
						""",
				UuidBinary.toBytes(sid), UuidBinary.toBytes(family), hash, Timestamp.from(expiresAt),
				UuidBinary.toBytes(account.publicId()));
	}

	@Override
	public Rotation rotateSession(byte[] currentHash, UUID newSid, byte[] newHash, Instant newExpiresAt) {
		return transactions.execute(status -> {
			var rows = jdbc.query(
					"""
							SELECT s.status,s.expires_at,s.token_family,u.public_id,u.email_normalized,c.password_hash,u.display_name,u.session_version
							FROM tn_auth_session s
							JOIN tn_user u ON u.id=s.user_id
							JOIN tn_auth_credential c ON c.user_id=u.id
							WHERE s.refresh_hash=? FOR UPDATE
							""",
					(result, row) -> new SessionRow(result.getString("status"),
							result.getTimestamp("expires_at").toInstant(),
							UuidBinary.fromBytes(result.getBytes("token_family")),
							account(result.getBytes("public_id"), result.getString("email_normalized"),
									result.getString("password_hash"), result.getString("display_name"),
									result.getLong("session_version"))),
					currentHash);
			if (rows.isEmpty())
				return Rotation.invalid(RotationStatus.INVALID);
			var current = rows.getFirst();
			if ("ROTATED".equals(current.status())) {
				jdbc.update(
						"UPDATE tn_auth_session SET status='REVOKED',updated_at=CURRENT_TIMESTAMP(6) WHERE token_family=?",
						UuidBinary.toBytes(current.family()));
				return Rotation.invalid(RotationStatus.REUSED);
			}
			if (!"ACTIVE".equals(current.status()))
				return Rotation.invalid(RotationStatus.INVALID);
			if (!current.expiresAt().isAfter(Instant.now()))
				return Rotation.invalid(RotationStatus.EXPIRED);
			jdbc.update(
					"UPDATE tn_auth_session SET status='ROTATED',updated_at=CURRENT_TIMESTAMP(6) WHERE refresh_hash=?",
					currentHash);
			createSession(current.account(), newSid, current.family(), newHash, newExpiresAt);
			return new Rotation(RotationStatus.ROTATED, current.account(), current.family());
		});
	}

	@Override
	public void revokeSession(byte[] refreshHash) {
		jdbc.update("UPDATE tn_auth_session SET status='REVOKED',updated_at=CURRENT_TIMESTAMP(6) WHERE refresh_hash=?",
				refreshHash);
	}

	@Override
	public boolean isAccessSessionActive(UUID userId, UUID sid, long sessionVersion) {
		var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM tn_auth_session s JOIN tn_user u ON u.id=s.user_id
				WHERE u.public_id=? AND s.sid=? AND u.session_version=? AND u.status='ACTIVE'
				  AND s.status='ACTIVE' AND s.expires_at>CURRENT_TIMESTAMP(6)
				""", Integer.class, UuidBinary.toBytes(userId), UuidBinary.toBytes(sid), sessionVersion);
		return count != null && count == 1;
	}

	@Override
	public boolean isLoginLocked(String normalizedEmail, Instant now) {
		var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM tn_auth_credential c JOIN tn_user u ON u.id=c.user_id
				WHERE u.email_normalized=? AND c.locked_until>?
				""", Integer.class, normalizedEmail, Timestamp.from(now));
		return count != null && count > 0;
	}

	@Override
	public void recordLoginFailure(String normalizedEmail, Instant now, int threshold, Instant lockedUntil) {
		jdbc.update("""
				UPDATE tn_auth_credential c JOIN tn_user u ON u.id=c.user_id
				SET c.failed_attempt=c.failed_attempt+1,
				    c.locked_until=CASE WHEN c.failed_attempt+1>=? THEN ? ELSE c.locked_until END,
				    c.updated_at=CURRENT_TIMESTAMP(6)
				WHERE u.email_normalized=? AND c.status='ACTIVE'
				""", threshold, Timestamp.from(lockedUntil), normalizedEmail);
	}

	@Override
	public void recordLoginSuccess(String normalizedEmail) {
		jdbc.update("""
				UPDATE tn_auth_credential c JOIN tn_user u ON u.id=c.user_id
				SET c.failed_attempt=0,c.locked_until=NULL,c.updated_at=CURRENT_TIMESTAMP(6)
				WHERE u.email_normalized=?
				""", normalizedEmail);
	}

	private static StoredAccount account(byte[] id, String email, String hash, String name, long version) {
		return new StoredAccount(UuidBinary.fromBytes(id), email, hash, name, version);
	}

	private record SessionRow(String status, Instant expiresAt, UUID family, StoredAccount account) {
	}
}
