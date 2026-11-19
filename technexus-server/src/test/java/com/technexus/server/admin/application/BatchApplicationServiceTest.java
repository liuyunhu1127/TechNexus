package com.technexus.server.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.technexus.server.admin.infrastructure.InMemoryBatchJobRepository;
import com.technexus.server.community.application.CommunityApplicationService;
import com.technexus.server.community.infrastructure.InMemoryCommentRepository;
import com.technexus.server.community.infrastructure.InMemoryNotificationRepository;
import com.technexus.server.community.infrastructure.InMemoryTagRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BatchApplicationServiceTest {
	@Test
	void persistsPerItemResultsWhenOneTagHasAStaleVersion() {
		var tags = new InMemoryTagRepository();
		var community = new CommunityApplicationService(new InMemoryCommentRepository(),
				new InMemoryNotificationRepository(), tags);
		var repository = new InMemoryBatchJobRepository();
		var service = new BatchApplicationService(repository, community);
		var actor = UUID.randomUUID();
		var first = community.createTag(actor,
				new CommunityApplicationService.TagCreateCommand("first", List.of(), "batch test"));
		var second = community.createTag(actor,
				new CommunityApplicationService.TagCreateCommand("second", List.of(), "batch test"));

		var result = service.executeTagStatusBatch(actor,
				new BatchApplicationService.TagStatusBatchCommand(false,
						List.of(new BatchApplicationService.TagStatusItem(first.id(), first.version()),
								new BatchApplicationService.TagStatusItem(second.id(), 99)),
						"moderation"));

		assertEquals("PARTIAL_FAILED", result.status());
		assertEquals(1, result.successCount());
		assertEquals(1, result.failureCount());
		assertEquals("SUCCEEDED", result.items().get(0).status());
		assertEquals("VERSION_CONFLICT", result.items().get(1).errorCode());
		assertEquals(false, community.getTag(first.id()).enabled());
		assertEquals(true, community.getTag(second.id()).enabled());
		assertEquals(2, service.getBatch(result.id()).items().size());
	}
}
