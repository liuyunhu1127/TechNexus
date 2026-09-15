package com.technexus.server.admin.infrastructure;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditResult;
import com.technexus.audit.domain.AuditState;
import com.technexus.audit.domain.AuditTask;
import com.technexus.audit.domain.Decision;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
public class JdbcAuditTaskRepository implements AuditTaskRepository {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcAuditTaskRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public Optional<AuditTask> findById(UUID publicId) {
		return jdbc.query("""
				SELECT t.public_id,t.target_type,t.target_public_id,t.target_version_id,t.state,t.version,
				       assignee.public_id assignee_public_id,r.decision,r.reason_code,r.note,
				       reviewer.public_id reviewer_public_id,r.decided_at
				FROM tn_audit_task t
				LEFT JOIN tn_user assignee ON assignee.id=t.assignee_id
				LEFT JOIN tn_audit_result r ON r.task_id=t.id
				LEFT JOIN tn_user reviewer ON reviewer.id=r.reviewer_id
				WHERE t.public_id=?
				""", this::restore, bytes(publicId)).stream().findFirst();
	}

	@Override
	public Optional<AuditTask> findByTarget(TargetRef target, UUID targetVersionId) {
		return jdbc.query("""
				SELECT public_id FROM tn_audit_task
				WHERE target_type=? AND target_public_id=? AND target_version_id=? AND audit_kind=?
				""", (result, row) -> uuid(result.getBytes(1)), target.type(), bytes(target.publicId()),
				bytes(targetVersionId), auditKind(target.type())).stream().findFirst().flatMap(this::findById);
	}

	@Override
	public List<AuditTask> list(AuditState state, int limit) {
		var ids = state == null
				? jdbc.query("SELECT public_id FROM tn_audit_task ORDER BY priority DESC,created_at,public_id LIMIT ?",
						(result, row) -> uuid(result.getBytes(1)), bounded(limit))
				: jdbc.query(
						"SELECT public_id FROM tn_audit_task WHERE state=? ORDER BY priority DESC,created_at,public_id LIMIT ?",
						(result, row) -> uuid(result.getBytes(1)), state.name(), bounded(limit));
		return ids.stream().map(id -> findById(id).orElseThrow()).toList();
	}

	@Override
	public void add(AuditTask task) {
		var changed = jdbc.update("""
				INSERT INTO tn_audit_task(public_id,target_type,target_public_id,target_version_id,audit_kind,state,
				                          priority,assignee_id,version,created_at,updated_at)
				VALUES (?,?,?,?,?,?,0,NULL,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
				""", bytes(task.publicId()), task.target().type(), bytes(task.target().publicId()),
				bytes(task.targetVersionId()), auditKind(task.target().type()), task.state().name(), task.version());
		if (changed != 1)
			throw new DomainException("AUDIT_CREATE_FAILED", "审核任务创建失败");
	}

	@Override
	public void save(AuditTask task, long expectedVersion) {
		transactions.executeWithoutResult(status -> {
			var changed = jdbc.update("""
					UPDATE tn_audit_task SET state=?,assignee_id=(SELECT id FROM tn_user WHERE public_id=?),
					                         version=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=?
					""", task.state().name(), nullableBytes(task.assigneeId()), task.version(), bytes(task.publicId()),
					expectedVersion);
			if (changed != 1)
				throw new DomainException("VERSION_CONFLICT", "审核任务版本已变化");
			if (task.result() != null)
				insertResult(task, task.result());
		});
	}

	private void insertResult(AuditTask task, AuditResult result) {
		var changed = jdbc.update(
				"""
						INSERT INTO tn_audit_result(task_id,decision,reason_code,note,reviewer_id,decided_at,created_at,updated_at)
						SELECT t.id,?,?,?,u.id,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
						FROM tn_audit_task t JOIN tn_user u ON u.public_id=?
						WHERE t.public_id=?
						""",
				result.decision().name(), result.reasonCode(), result.note(), Timestamp.from(result.decidedAt()),
				bytes(result.reviewerId()), bytes(task.publicId()));
		if (changed != 1)
			throw new DomainException("AUDIT_REVIEWER_NOT_FOUND", "审核人不存在");
	}

	private AuditTask restore(ResultSet result, int row) throws SQLException {
		AuditResult auditResult = null;
		if (result.getString("decision") != null) {
			auditResult = new AuditResult(Decision.valueOf(result.getString("decision")),
					result.getString("reason_code"), result.getString("note"),
					uuid(result.getBytes("reviewer_public_id")), result.getTimestamp("decided_at").toInstant());
		}
		return AuditTask.restore(uuid(result.getBytes("public_id")),
				new TargetRef(result.getString("target_type"), uuid(result.getBytes("target_public_id"))),
				uuid(result.getBytes("target_version_id")), AuditState.valueOf(result.getString("state")),
				nullableUuid(result.getBytes("assignee_public_id")), auditResult, result.getLong("version"));
	}

	private static int bounded(int limit) {
		return Math.min(Math.max(limit, 1), 100);
	}
	private static String auditKind(String targetType) {
		return "DEMAND".equals(targetType) ? "DEMAND" : "CONTENT";
	}
	private static byte[] nullableBytes(UUID value) {
		return value == null ? null : bytes(value);
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID nullableUuid(byte[] value) {
		return value == null ? null : uuid(value);
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
