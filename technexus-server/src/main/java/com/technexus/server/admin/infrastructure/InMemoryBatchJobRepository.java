package com.technexus.server.admin.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.port.BatchJobRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryBatchJobRepository implements BatchJobRepository {
	private final ConcurrentHashMap<UUID, BatchJobRecord> values = new ConcurrentHashMap<>();

	@Override
	public BatchJobRecord create(UUID id, String operation, int totalCount, UUID actorId, String reason) {
		var value = new BatchJobRecord(id, operation, "RUNNING", totalCount, 0, 0, actorId, reason, Instant.now(),
				List.of());
		values.put(id, value);
		return value;
	}

	@Override
	public void addResult(UUID batchId, BatchItemRecord result) {
		values.compute(batchId, (id, current) -> {
			if (current == null)
				throw new DomainException("RESOURCE_NOT_FOUND", "批量任务不存在");
			var items = new ArrayList<>(current.items());
			items.add(result);
			return copy(current, current.status(), current.successCount(), current.failureCount(), items);
		});
	}

	@Override
	public BatchJobRecord complete(UUID batchId, int successCount, int failureCount) {
		return values.compute(batchId, (id, current) -> {
			if (current == null)
				throw new DomainException("RESOURCE_NOT_FOUND", "批量任务不存在");
			var status = failureCount == 0 ? "SUCCEEDED" : successCount == 0 ? "FAILED" : "PARTIAL_FAILED";
			return copy(current, status, successCount, failureCount, current.items());
		});
	}

	@Override
	public List<BatchJobRecord> list(int limit) {
		return values.values().stream().sorted(Comparator.comparing(BatchJobRecord::createdAt).reversed()).limit(limit)
				.map(value -> copy(value, value.status(), value.successCount(), value.failureCount(), List.of()))
				.toList();
	}

	@Override
	public Optional<BatchJobRecord> findById(UUID id) {
		return Optional.ofNullable(values.get(id));
	}

	private static BatchJobRecord copy(BatchJobRecord value, String status, int success, int failure,
			List<BatchItemRecord> items) {
		return new BatchJobRecord(value.id(), value.operation(), status, value.totalCount(), success, failure,
				value.requestedBy(), value.reason(), value.createdAt(), List.copyOf(items));
	}
}
