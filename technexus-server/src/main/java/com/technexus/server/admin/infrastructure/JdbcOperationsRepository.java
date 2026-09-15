package com.technexus.server.admin.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.port.OperationsRepository;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcOperationsRepository implements OperationsRepository {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	public JdbcOperationsRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.json = json;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public Optional<ConfigRecord> findConfig(String key) {
		return jdbc
				.query("SELECT config_key,value_json,version FROM tn_system_config WHERE config_key=?",
						(result, row) -> new ConfigRecord(result.getString("config_key"),
								readObject(result.getString("value_json")), result.getLong("version")),
						key)
				.stream().findFirst();
	}

	@Override
	public ConfigRecord saveConfig(String key, Object value, long expectedVersion, UUID actorId, String reason) {
		return transactions.execute(status -> {
			var encoded = write(value);
			var current = findConfig(key);
			if (current.isEmpty()) {
				if (expectedVersion != 0)
					throw new DomainException("VERSION_CONFLICT", "配置版本已变化");
				jdbc.update("""
						INSERT INTO tn_system_config(config_key,value_json,version,created_at,updated_at)
						VALUES (?,?,1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
						""", key, encoded);
			} else {
				var changed = jdbc.update("""
						UPDATE tn_system_config SET value_json=?,version=version+1,updated_at=CURRENT_TIMESTAMP(6)
						WHERE config_key=? AND version=?
						""", encoded, key, expectedVersion);
				if (changed != 1)
					throw new DomainException("VERSION_CONFLICT", "配置版本已变化");
			}
			audit(actorId, "CONFIG_UPDATE", Map.of("key", key, "reason", reason));
			return findConfig(key).orElseThrow();
		});
	}

	@Override
	public AiSuggestionRecord addSuggestion(AiSuggestionRecord value) {
		var changed = jdbc.update("""
				INSERT INTO tn_ai_suggestion(public_id,kind,target_type,target_public_id,target_version_id,suggestion,
				                             state,output_json,version,created_at,updated_at)
				VALUES (?,?,?,?,?,?,'QUEUED',NULL,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
				""", bytes(value.id()), value.kind(), value.targetType(), bytes(value.targetId()),
				bytes(value.targetVersionId()), write(Map.of("isSuggestion", true)));
		if (changed != 1)
			throw new DomainException("AI_SUGGESTION_CREATE_FAILED", "AI 建议任务创建失败");
		return findSuggestion(value.id()).orElseThrow();
	}

	@Override
	public Optional<AiSuggestionRecord> findSuggestion(UUID id) {
		return jdbc.query("""
				SELECT public_id,kind,target_type,target_public_id,target_version_id,state,output_json,version
				FROM tn_ai_suggestion WHERE public_id=?
				""",
				(result, row) -> new AiSuggestionRecord(uuid(result.getBytes("public_id")), result.getString("kind"),
						result.getString("target_type"), uuid(result.getBytes("target_public_id")),
						uuid(result.getBytes("target_version_id")), result.getString("state"), true,
						readMap(result.getString("output_json")), result.getLong("version")),
				bytes(id)).stream().findFirst();
	}

	@Override
	public Optional<AiSuggestionJob> claimNextSuggestion(Instant now, Instant leaseUntil) {
		return transactions.execute(status -> {
			var candidate = jdbc
					.query("""
							SELECT public_id,kind,target_type,target_public_id,target_version_id,attempt_count,version
							FROM tn_ai_suggestion
							WHERE (state='QUEUED' AND available_at<=?) OR (state='RUNNING' AND lease_until<?)
							ORDER BY available_at,created_at,public_id LIMIT 1 FOR UPDATE SKIP LOCKED
							""", (result, row) -> new AiSuggestionJob(uuid(result.getBytes("public_id")),
							result.getString("kind"), result.getString("target_type"),
							uuid(result.getBytes("target_public_id")), uuid(result.getBytes("target_version_id")),
							result.getInt("attempt_count") + 1, result.getLong("version") + 1), now, now)
					.stream().findFirst();
			if (candidate.isEmpty())
				return Optional.empty();
			var job = candidate.get();
			var changed = jdbc.update("""
					UPDATE tn_ai_suggestion SET state='RUNNING',attempt_count=?,lease_until=?,last_error=NULL,
					                            version=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=?
					""", job.attempt(), leaseUntil, job.version(), bytes(job.id()), job.version() - 1);
			if (changed != 1)
				throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
			return Optional.of(job);
		});
	}

	@Override
	public void completeSuggestion(UUID id, long expectedVersion, Map<String, Object> output) {
		transitionJob(id, expectedVersion, "SUCCEEDED", null, null, write(output));
	}

	@Override
	public void retrySuggestion(UUID id, long expectedVersion, Instant retryAt, String error) {
		transitionJob(id, expectedVersion, "QUEUED", retryAt, error, null);
	}

	@Override
	public void failSuggestion(UUID id, long expectedVersion, String error) {
		transitionJob(id, expectedVersion, "FAILED", null, error, null);
	}

	private void transitionJob(UUID id, long expectedVersion, String state, Instant availableAt, String error,
			String outputJson) {
		var changed = jdbc.update("""
				UPDATE tn_ai_suggestion SET state=?,available_at=COALESCE(?,available_at),lease_until=NULL,
				                            last_error=?,output_json=COALESCE(?,output_json),version=version+1,
				                            updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND version=? AND state='RUNNING'
				""", state, availableAt, error, outputJson, bytes(id), expectedVersion);
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
	}

	@Override
	public AiSuggestionRecord decideSuggestion(UUID id, String decision, Map<String, Object> editedOutput,
			long expectedVersion, UUID actorId) {
		return transactions.execute(status -> {
			var current = findSuggestion(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "AI 建议不存在"));
			if (current.version() != expectedVersion)
				throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
			if (!"SUCCEEDED".equals(current.state()))
				throw new DomainException("AI_SUGGESTION_NOT_READY", "AI 建议尚未完成");
			var stateValue = switch (decision) {
				case "ACCEPT" -> "ACCEPTED";
				case "EDIT" -> "EDITED";
				case "REJECT" -> "REJECTED";
				default -> throw new DomainException("AI_DECISION_INVALID", "AI 建议决定无效");
			};
			var output = "EDIT".equals(decision) ? editedOutput : current.output();
			var changed = jdbc.update("""
					UPDATE tn_ai_suggestion SET decision=?,decided_by=(SELECT id FROM tn_user WHERE public_id=?),
					                            decided_at=CURRENT_TIMESTAMP(6),state=?,output_json=?,version=version+1,
					                            updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=? AND state='SUCCEEDED'
					""", decision, bytes(actorId), stateValue, write(output), bytes(id), expectedVersion);
			if (changed != 1)
				throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
			audit(actorId, "AI_SUGGESTION_" + decision, Map.of("suggestionId", id.toString()));
			return findSuggestion(id).orElseThrow();
		});
	}

	@Override
	public DashboardRecord dashboard() {
		var pendingAudit = jdbc.queryForObject(
				"SELECT COUNT(*) FROM tn_audit_task WHERE state IN ('CREATED','PENDING','PROCESSING')", Long.class);
		var failedJobs = jdbc.queryForObject("SELECT COUNT(*) FROM tn_outbox_event WHERE status='FAILED'", Long.class);
		var storageProblems = jdbc.queryForObject("""
				SELECT COUNT(*) FROM tn_file_object
				WHERE state='BLOCKED' OR (state='SCANNING' AND updated_at<CURRENT_TIMESTAMP(6)-INTERVAL 10 MINUTE)
				""", Long.class);
		var backupConfigured = jdbc.queryForObject(
				"SELECT COUNT(*) FROM tn_system_config WHERE config_key='backup.last_success'", Long.class);
		return new DashboardRecord(value(pendingAudit), 0, value(failedJobs), value(storageProblems) > 0,
				value(backupConfigured) == 0);
	}

	private void audit(UUID actorId, String action, Map<String, Object> target) {
		var changed = jdbc.update("""
				INSERT INTO tn_operation_audit(actor_id,action,target,created_at,updated_at)
				SELECT id,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
				""", action, write(target), bytes(actorId));
		if (changed != 1)
			throw new DomainException("AUTH_REQUIRED", "操作人不存在");
	}

	private Object readObject(String value) {
		try {
			return json.readValue(value, Object.class);
		} catch (Exception error) {
			throw new IllegalStateException("Could not read JSON", error);
		}
	}
	private Map<String, Object> readMap(String value) {
		if (value == null)
			return Map.of();
		try {
			return json.readValue(value, new TypeReference<>() {
			});
		} catch (Exception error) {
			throw new IllegalStateException("Could not read JSON", error);
		}
	}
	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception error) {
			throw new DomainException("JSON_VALUE_INVALID", "值不能序列化为 JSON");
		}
	}
	private static long value(Long value) {
		return value == null ? 0 : value;
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
