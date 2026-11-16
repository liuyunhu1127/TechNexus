package com.technexus.server.admin.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.port.BatchJobRepository;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcBatchJobRepository implements BatchJobRepository {
	private static final String JOB_SELECT = """
			SELECT j.public_id,j.operation,j.status,j.total_count,j.success_count,j.failure_count,
			       u.public_id AS requested_by,j.reason,j.created_at
			FROM tn_batch_job j JOIN tn_user u ON u.id=j.requested_by
			""";
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcBatchJobRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public BatchJobRecord create(UUID id, String operation, int totalCount, UUID actorId, String reason) {
		return transactions.execute(status -> {
			var changed = jdbc.update("""
					INSERT INTO tn_batch_job(public_id,operation,status,total_count,success_count,failure_count,
					                         requested_by,reason,created_at,updated_at)
					SELECT ?,?,'RUNNING',?,0,0,id,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
					FROM tn_user WHERE public_id=?
					""", bytes(id), operation, totalCount, reason, bytes(actorId));
			if (changed != 1)
				throw new DomainException("AUTH_REQUIRED", "操作人不存在");
			return findById(id).orElseThrow();
		});
	}

	@Override
	public void addResult(UUID batchId, BatchItemRecord result) {
		var changed = jdbc.update("""
				INSERT INTO tn_batch_job_item(batch_job_id,sequence_no,target_type,target_public_id,requested_version,
				                              status,error_code,result_version,created_at,updated_at)
				SELECT id,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
				FROM tn_batch_job WHERE public_id=? AND status='RUNNING'
				""", result.sequence(), result.targetType(), bytes(result.targetId()), result.requestedVersion(),
				result.status(), result.errorCode(), result.resultVersion(), bytes(batchId));
		if (changed != 1)
			throw new DomainException("BATCH_STATE_INVALID", "批量任务状态无效");
	}

	@Override
	public BatchJobRecord complete(UUID batchId, int successCount, int failureCount) {
		var status = failureCount == 0 ? "SUCCEEDED" : successCount == 0 ? "FAILED" : "PARTIAL_FAILED";
		var changed = jdbc.update("""
				UPDATE tn_batch_job SET status=?,success_count=?,failure_count=?,updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND status='RUNNING' AND total_count=?
				""", status, successCount, failureCount, bytes(batchId), successCount + failureCount);
		if (changed != 1)
			throw new DomainException("BATCH_STATE_INVALID", "批量任务结果数量或状态无效");
		return findById(batchId).orElseThrow();
	}

	@Override
	public List<BatchJobRecord> list(int limit) {
		return jdbc.query(JOB_SELECT + " ORDER BY j.created_at DESC,j.id DESC LIMIT ?", (result, row) -> job(result),
				limit);
	}

	@Override
	public Optional<BatchJobRecord> findById(UUID id) {
		var job = jdbc.query(JOB_SELECT + " WHERE j.public_id=?", (result, row) -> job(result), bytes(id)).stream()
				.findFirst();
		if (job.isEmpty())
			return Optional.empty();
		var items = jdbc.query(
				"""
						SELECT i.sequence_no,i.target_type,i.target_public_id,i.requested_version,i.status,i.error_code,i.result_version
						FROM tn_batch_job_item i JOIN tn_batch_job j ON j.id=i.batch_job_id
						WHERE j.public_id=? ORDER BY i.sequence_no
						""",
				(result, row) -> new BatchItemRecord(result.getInt("sequence_no"), result.getString("target_type"),
						uuid(result.getBytes("target_public_id")), result.getLong("requested_version"),
						result.getString("status"), result.getString("error_code"),
						result.getObject("result_version", Long.class)),
				bytes(id));
		var value = job.get();
		return Optional.of(new BatchJobRecord(value.id(), value.operation(), value.status(), value.totalCount(),
				value.successCount(), value.failureCount(), value.requestedBy(), value.reason(), value.createdAt(),
				items));
	}

	private static BatchJobRecord job(java.sql.ResultSet result) throws java.sql.SQLException {
		return new BatchJobRecord(uuid(result.getBytes("public_id")), result.getString("operation"),
				result.getString("status"), result.getInt("total_count"), result.getInt("success_count"),
				result.getInt("failure_count"), uuid(result.getBytes("requested_by")), result.getString("reason"),
				result.getTimestamp("created_at").toInstant(), List.of());
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
