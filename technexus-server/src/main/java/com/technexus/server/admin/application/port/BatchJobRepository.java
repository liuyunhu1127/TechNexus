package com.technexus.server.admin.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchJobRepository {
	BatchJobRecord create(UUID id, String operation, int totalCount, UUID actorId, String reason);
	void addResult(UUID batchId, BatchItemRecord result);
	BatchJobRecord complete(UUID batchId, int successCount, int failureCount);
	List<BatchJobRecord> list(int limit);
	Optional<BatchJobRecord> findById(UUID id);

	record BatchItemRecord(int sequence, String targetType, UUID targetId, long requestedVersion, String status,
			String errorCode, Long resultVersion) {
	}
	record BatchJobRecord(UUID id, String operation, String status, int totalCount, int successCount, int failureCount,
			UUID requestedBy, String reason, Instant createdAt, List<BatchItemRecord> items) {
	}
}
