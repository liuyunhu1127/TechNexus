package com.technexus.audit.api;

import com.technexus.common.domain.TargetRef;
import java.util.UUID;

public interface AuditPort {
	UUID createTask(TargetRef target, UUID targetVersionId);
}
