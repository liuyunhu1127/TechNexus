package com.technexus.server.auth.infrastructure;

import com.technexus.server.user.application.EmailActionStore;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcEmailActionStore implements EmailActionStore {
	private final JdbcTemplate jdbc;

	public JdbcEmailActionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void replace(Action action) {
		jdbc.update(
				"""
						INSERT INTO tn_account_email_action
						  (user_id,action_type,public_id,current_email,target_email,session_version,first_digest,second_digest,expires_at)
						SELECT u.id,?,?,?,?,?,?,?,? FROM tn_user u WHERE u.public_id=?
						ON DUPLICATE KEY UPDATE public_id=?,current_email=?,target_email=?,session_version=?,
						  first_digest=?,second_digest=?,expires_at=?
						""",
				action.kind().name(), UuidBinary.toBytes(action.id()), action.currentEmail(), action.targetEmail(),
				action.sessionVersion(), action.firstDigest(), action.secondDigest(),
				Timestamp.from(action.expiresAt()), UuidBinary.toBytes(action.userId()),
				UuidBinary.toBytes(action.id()), action.currentEmail(), action.targetEmail(), action.sessionVersion(),
				action.firstDigest(), action.secondDigest(), Timestamp.from(action.expiresAt()));
	}

	@Override
	public Optional<Action> lock(UUID id, Kind kind) {
		return jdbc.query("""
				SELECT a.*,u.public_id AS account_id FROM tn_account_email_action a
				JOIN tn_user u ON u.id=a.user_id WHERE a.public_id=? AND a.action_type=? FOR UPDATE
				""",
				(row, index) -> new Action(id, UuidBinary.fromBytes(row.getBytes("account_id")), kind,
						row.getString("current_email"), row.getString("target_email"), row.getLong("session_version"),
						row.getBytes("first_digest"), row.getBytes("second_digest"),
						row.getTimestamp("expires_at").toInstant()),
				UuidBinary.toBytes(id), kind.name()).stream().findFirst();
	}

	@Override
	public boolean consume(UUID id) {
		return jdbc.update("DELETE FROM tn_account_email_action WHERE public_id=?", UuidBinary.toBytes(id)) == 1;
	}
}
