package com.technexus.server.admin.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.UuidV7;
import com.technexus.server.admin.application.port.BatchJobRepository;
import com.technexus.server.community.application.CommunityApplicationService;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class BatchApplicationService {
	private final BatchJobRepository batches;
	private final CommunityApplicationService community;

	public BatchApplicationService(BatchJobRepository batches, CommunityApplicationService community) {
		this.batches = batches;
		this.community = community;
	}

	public BatchJobView executeTagStatusBatch(UUID actorId, TagStatusBatchCommand command) {
		validate(command);
		var operation = command.enabled() ? "TAG_ENABLE" : "TAG_DISABLE";
		var batch = batches.create(UuidV7.generate(), operation, command.items().size(), actorId, command.reason());
		var success = 0;
		var failure = 0;
		for (var index = 0; index < command.items().size(); index++) {
			var item = command.items().get(index);
			try {
				var current = community.getTag(item.tagId());
				var updated = community.updateTag(item.tagId(), actorId,
						new CommunityApplicationService.TagUpdateCommand(command.enabled(), current.aliases(),
								item.expectedVersion(), command.reason()));
				batches.addResult(batch.id(), new BatchJobRepository.BatchItemRecord(index + 1, "TAG", item.tagId(),
						item.expectedVersion(), "SUCCEEDED", null, updated.version()));
				success++;
			} catch (DomainException error) {
				batches.addResult(batch.id(), new BatchJobRepository.BatchItemRecord(index + 1, "TAG", item.tagId(),
						item.expectedVersion(), "FAILED", error.code(), null));
				failure++;
			}
		}
		return view(batches.complete(batch.id(), success, failure));
	}

	public List<BatchJobView> listBatches() {
		return batches.list(100).stream().map(BatchApplicationService::view).toList();
	}

	public BatchJobView getBatch(UUID id) {
		return batches.findById(id).map(BatchApplicationService::view)
				.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "批量任务不存在"));
	}

	private static void validate(TagStatusBatchCommand command) {
		if (command.items() == null || command.items().isEmpty() || command.items().size() > 50)
			throw new DomainException("BATCH_SIZE_INVALID", "批量任务必须包含 1 至 50 项");
		if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 1000)
			throw new DomainException("BATCH_REASON_INVALID", "批量任务原因无效");
		var ids = new HashSet<UUID>();
		if (command.items().stream().anyMatch(
				item -> item == null || item.tagId() == null || item.expectedVersion() < 0 || !ids.add(item.tagId())))
			throw new DomainException("BATCH_ITEM_INVALID", "批量任务目标重复或无效");
	}

	private static BatchJobView view(BatchJobRepository.BatchJobRecord value) {
		return new BatchJobView(value.id(), value.operation(), value.status(), value.totalCount(), value.successCount(),
				value.failureCount(), value.requestedBy(), value.reason(), value.createdAt(), value.items());
	}

	public record TagStatusItem(UUID tagId, long expectedVersion) {
	}
	public record TagStatusBatchCommand(boolean enabled, List<TagStatusItem> items, String reason) {
	}
	public record BatchJobView(UUID id, String operation, String status, int totalCount, int successCount,
			int failureCount, UUID requestedBy, String reason, java.time.Instant createdAt,
			List<BatchJobRepository.BatchItemRecord> items) {
	}
}
