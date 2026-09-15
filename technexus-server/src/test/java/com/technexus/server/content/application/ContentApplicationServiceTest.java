package com.technexus.server.content.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.server.content.infrastructure.InMemoryArticleRepository;
import com.technexus.server.content.infrastructure.InMemoryPostRepository;
import com.technexus.server.admin.infrastructure.InMemoryAuditTaskRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentApplicationServiceTest {
	private final InMemoryAuditTaskRepository audits = new InMemoryAuditTaskRepository();
	private final ContentApplicationService service = new ContentApplicationService(new InMemoryArticleRepository(),
			new InMemoryPostRepository(), audits);
	private final UUID owner = UUID.randomUUID();

	@Test
	void createsRevisesAndSubmitsDocumentAggregate() {
		var created = service.create(owner, command("DOCUMENT", "OWNER_ONLY", "First"));
		var revised = service.update(created.id(), owner, 0,
				new ContentApplicationService.ContentCommand(null, "OWNER_ONLY", "Second", "Summary", "Body 2"));
		var submitted = service.submit(created.id(), owner, revised.version());

		assertEquals("Second", submitted.title());
		assertEquals("PENDING_REVIEW", submitted.state());
		assertEquals(2, submitted.version());
		assertNotNull(submitted.auditTaskId());
		assertEquals(submitted.auditTaskId(), service.submit(created.id(), owner, submitted.version()).auditTaskId());
	}

	@Test
	void createsProblemAsPostAggregate() {
		var created = service.create(owner, command("PROBLEM", "PUBLIC", "Problem"));

		assertEquals("PROBLEM", created.type());
		assertEquals("DRAFT", created.state());
	}

	@Test
	void rejectsStaleContentVersion() {
		var created = service.create(owner, command("POST", "OWNER_ONLY", "Post"));

		var error = assertThrows(DomainException.class, () -> service.update(created.id(), owner, 2,
				new ContentApplicationService.ContentCommand(null, null, "Changed", null, null)));

		assertEquals("VERSION_CONFLICT", error.code());
	}

	@Test
	void hidesPrivateDraftFromOtherUser() {
		var created = service.create(owner, command("DOCUMENT", "OWNER_ONLY", "Private"));

		var error = assertThrows(DomainException.class, () -> service.get(created.id(), UUID.randomUUID()));

		assertEquals("RESOURCE_NOT_FOUND", error.code());
	}

	private static ContentApplicationService.ContentCommand command(String type, String visibility, String title) {
		return new ContentApplicationService.ContentCommand(type, visibility, title, "Summary", "Body");
	}
}
