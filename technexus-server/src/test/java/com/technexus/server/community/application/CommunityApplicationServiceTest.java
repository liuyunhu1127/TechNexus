package com.technexus.server.community.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import com.technexus.server.community.infrastructure.InMemoryCommentRepository;
import com.technexus.server.community.infrastructure.InMemoryNotificationRepository;
import com.technexus.server.community.infrastructure.InMemoryTagRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunityApplicationServiceTest {
	private final InMemoryNotificationRepository notifications = new InMemoryNotificationRepository();
	private final CommunityApplicationService service = new CommunityApplicationService(new InMemoryCommentRepository(),
			notifications, new InMemoryTagRepository());

	@Test
	void createsPublishedComment() {
		var comment = service.createComment(UUID.randomUUID(), UUID.randomUUID(), null, "Useful");
		assertEquals("PUBLISHED", comment.state());
	}

	@Test
	void hidesUnknownNotificationAsNotFound() {
		var error = assertThrows(DomainException.class,
				() -> service.readNotification(UUID.randomUUID(), UUID.randomUUID()));
		assertEquals("RESOURCE_NOT_FOUND", error.code());
	}

	@Test
	void replyCreatesNotificationAndDeletingParentKeepsChildThread() {
		var content = UUID.randomUUID();
		var author = UUID.randomUUID();
		var replier = UUID.randomUUID();
		var parent = service.createComment(author, content, null, "Parent");
		var child = service.createComment(replier, content, parent.id(), "Child");

		assertEquals("REPLY", service.listNotifications(author).getFirst().type());
		service.deleteComment(author, content, parent.id(), parent.version());
		var thread = service.listComments(content);
		assertEquals("DELETED", thread.getFirst().state());
		assertEquals("", thread.getFirst().body());
		assertEquals(parent.id(), thread.get(1).parentId());
		assertEquals(child.id(), thread.get(1).id());
		service.readNotification(author, service.listNotifications(author).getFirst().id());
		assertTrue(service.listNotifications(author).getFirst().readAt() != null);
	}

	@Test
	void createsUpdatesAndMergesTagsWithOptimisticVersions() {
		var actor = UUID.randomUUID();
		var source = service.createTag(actor,
				new CommunityApplicationService.TagCreateCommand("JDK", List.of("Java SDK"), "standardize"));
		var target = service.createTag(actor,
				new CommunityApplicationService.TagCreateCommand("Java", List.of(), "standardize"));
		var disabled = service.updateTag(source.id(), actor,
				new CommunityApplicationService.TagUpdateCommand(false, source.aliases(), source.version(), "pause"));
		var enabled = service.updateTag(source.id(), actor, new CommunityApplicationService.TagUpdateCommand(true,
				disabled.aliases(), disabled.version(), "resume"));

		var merged = service.mergeTag(source.id(), actor, new CommunityApplicationService.TagMergeCommand(target.id(),
				enabled.version(), target.version(), "canonical java tag"));

		assertEquals(target.id(), merged.source().mergedIntoId());
		assertEquals(false, merged.source().enabled());
		assertEquals(List.of("java sdk", "jdk"), merged.target().aliases());
		assertThrows(DomainException.class,
				() -> service.updateTag(source.id(), actor, new CommunityApplicationService.TagUpdateCommand(true,
						List.of(), merged.source().version(), "invalid")));
	}
}
