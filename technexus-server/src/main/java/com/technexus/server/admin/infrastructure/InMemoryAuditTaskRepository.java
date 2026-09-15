package com.technexus.server.admin.infrastructure;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditState;
import com.technexus.audit.domain.AuditTask;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryAuditTaskRepository implements AuditTaskRepository {
	private final ConcurrentHashMap<UUID, AuditTask> values = new ConcurrentHashMap<>();
	@Override
	public Optional<AuditTask> findById(UUID publicId) {
		return Optional.ofNullable(values.get(publicId)).map(InMemoryAuditTaskRepository::copy);
	}
	@Override
	public Optional<AuditTask> findByTarget(TargetRef target, UUID targetVersionId) {
		return values.values().stream()
				.filter(value -> value.target().equals(target) && value.targetVersionId().equals(targetVersionId))
				.findFirst().map(InMemoryAuditTaskRepository::copy);
	}
	@Override
	public List<AuditTask> list(AuditState state, int limit) {
		return values.values().stream().filter(value -> state == null || value.state() == state)
				.limit(Math.min(Math.max(limit, 1), 100)).map(InMemoryAuditTaskRepository::copy).toList();
	}
	@Override
	public void add(AuditTask task) {
		if (values.putIfAbsent(task.publicId(), copy(task)) != null)
			throw new DomainException("AUDIT_EXISTS", "审核任务已存在");
	}
	@Override
	public void save(AuditTask task, long expectedVersion) {
		var current = values.get(task.publicId());
		if (current == null || current.version() != expectedVersion)
			throw new DomainException("VERSION_CONFLICT", "审核任务版本已变化");
		values.put(task.publicId(), copy(task));
	}
	private static AuditTask copy(AuditTask task) {
		return AuditTask.restore(task.publicId(), task.target(), task.targetVersionId(), task.state(),
				task.assigneeId(), task.result(), task.version());
	}
}
