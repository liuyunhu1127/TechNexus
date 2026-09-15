package com.technexus.audit.api;

import com.technexus.audit.domain.AuditTask;
import com.technexus.audit.domain.AuditState;
import com.technexus.common.domain.TargetRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditTaskRepository {
	Optional<AuditTask> findById(UUID publicId);
	Optional<AuditTask> findByTarget(TargetRef target, UUID targetVersionId);
	List<AuditTask> list(AuditState state, int limit);
	void add(AuditTask task);
	void save(AuditTask task, long expectedVersion);
}
