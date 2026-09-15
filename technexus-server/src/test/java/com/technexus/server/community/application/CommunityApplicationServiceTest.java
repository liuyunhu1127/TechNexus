package com.technexus.server.community.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.server.community.infrastructure.InMemoryCommentRepository;
import com.technexus.server.community.infrastructure.InMemoryNotificationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunityApplicationServiceTest {
	private final CommunityApplicationService service = new CommunityApplicationService(new InMemoryCommentRepository(),
			new InMemoryNotificationRepository());

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
}
