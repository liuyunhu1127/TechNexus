package com.technexus.server.web.idempotency;

import java.nio.ByteBuffer;
import java.sql.Statement;
import java.util.Arrays;
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
public class JdbcIdempotencyStore implements IdempotencyStore {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcIdempotencyStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public Reservation reserve(UUID actorPublicId, String key, String method, String path, byte[] requestHash) {
		return transactions.execute(status -> {
			var actorId = actorId(actorPublicId);
			jdbc.update("DELETE FROM tn_idempotency WHERE expires_at<=CURRENT_TIMESTAMP(6) LIMIT 100");
			var current = find(actorId, key, method, path);
			if (current.isPresent())
				return evaluate(current.get(), requestHash);
			try {
				var keys = new GeneratedKeyHolder();
				jdbc.update(connection -> {
					var statement = connection.prepareStatement(
							"""
									INSERT INTO tn_idempotency(actor_id,idempotency_key,http_method,request_path,request_hash,
									                           expires_at,created_at,updated_at)
									VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 24 HOUR,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
									""",
							Statement.RETURN_GENERATED_KEYS);
					statement.setLong(1, actorId);
					statement.setString(2, key);
					statement.setString(3, method);
					statement.setString(4, path);
					statement.setBytes(5, requestHash);
					return statement;
				}, keys);
				return Reservation.acquired(keys.getKey().longValue());
			} catch (DuplicateKeyException race) {
				return evaluate(find(actorId, key, method, path).orElseThrow(), requestHash);
			}
		});
	}

	@Override
	public void complete(long reservationId, int responseStatus, String responseBody) {
		jdbc.update("""
				UPDATE tn_idempotency SET response_status=?,response_body=?,updated_at=CURRENT_TIMESTAMP(6)
				WHERE id=? AND response_status IS NULL
				""", responseStatus, responseBody == null || responseBody.isBlank() ? null : responseBody,
				reservationId);
	}

	@Override
	public void abandon(long reservationId) {
		jdbc.update("DELETE FROM tn_idempotency WHERE id=? AND response_status IS NULL", reservationId);
	}

	private Optional<StoredRequest> find(long actorId, String key, String method, String path) {
		return jdbc.query("""
				SELECT id,request_hash,response_status,response_body
				FROM tn_idempotency
				WHERE actor_id=? AND idempotency_key=? AND http_method=? AND request_path=?
				""",
				(result, row) -> new StoredRequest(result.getLong("id"), result.getBytes("request_hash"),
						(Integer) result.getObject("response_status"), result.getString("response_body")),
				actorId, key, method, path).stream().findFirst();
	}

	private long actorId(UUID actorPublicId) {
		if (actorPublicId == null)
			return 0;
		return jdbc.query("SELECT id FROM tn_user WHERE public_id=?", (result, row) -> result.getLong(1),
				bytes(actorPublicId)).stream().findFirst().orElse(0L);
	}

	private static Reservation evaluate(StoredRequest current, byte[] requestHash) {
		if (!Arrays.equals(current.requestHash(), requestHash))
			return Reservation.conflict(current.id());
		if (current.responseStatus() == null)
			return Reservation.inProgress(current.id());
		return Reservation.replay(current.id(), current.responseStatus(), current.responseBody());
	}

	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}

	private record StoredRequest(long id, byte[] requestHash, Integer responseStatus, String responseBody) {
	}
}
