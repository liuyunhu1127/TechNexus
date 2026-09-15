package com.technexus.server.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.infrastructure.InMemoryAuditTaskRepository;
import com.technexus.server.admin.infrastructure.InMemoryOperationsRepository;
import com.technexus.server.admin.infrastructure.InMemoryPriceObjectRepository;
import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.content.infrastructure.InMemoryArticleRepository;
import com.technexus.server.content.infrastructure.InMemoryPostRepository;
import com.technexus.server.demand.application.DemandApplicationService;
import com.technexus.server.demand.infrastructure.InMemoryDemandRepository;
import com.technexus.server.demand.infrastructure.InMemoryLineageRepository;
import com.technexus.server.demand.infrastructure.InMemoryProposalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminApplicationServiceTest {
	private InMemoryAuditTaskRepository audits;
	private InMemoryOperationsRepository operations;
	private InMemoryArticleRepository articles;
	private InMemoryPostRepository posts;
	private InMemoryDemandRepository demands;
	private AdminApplicationService service;

	@BeforeEach
	void setUp() {
		audits = new InMemoryAuditTaskRepository();
		operations = new InMemoryOperationsRepository();
		articles = new InMemoryArticleRepository();
		posts = new InMemoryPostRepository();
		demands = new InMemoryDemandRepository();
		service = new AdminApplicationService(audits, new InMemoryPriceObjectRepository(), operations, articles, posts,
				demands);
	}

	@Test
	void humanDecisionClaimsPendingTaskAndPublishesBoundContentVersion() {
		var owner = UUID.randomUUID();
		var content = new ContentApplicationService(articles, posts, audits);
		var created = content.create(owner,
				new ContentApplicationService.ContentCommand("DOCUMENT", "PUBLIC", "Title", "Summary", "Body"));
		var submitted = content.submit(created.id(), owner, created.version());
		var task = audits.findById(submitted.auditTaskId()).orElseThrow();

		var result = service.decideAuditTask(task.publicId(), UUID.randomUUID(),
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, task.version()));

		assertEquals("APPROVED", result.state());
		assertEquals(3, result.version());
		assertEquals("PUBLISHED", articles.findById(created.id()).orElseThrow().state().name());
	}

	@Test
	void rejectedDemandDecisionUpdatesOnlyTheBoundReviewVersion() {
		var owner = UUID.randomUUID();
		var demandService = new DemandApplicationService(demands, new InMemoryProposalRepository(),
				new InMemoryLineageRepository(), audits);
		var created = demandService.create(owner, new DemandApplicationService.DemandCommand("Need", "Description",
				BigDecimal.ZERO, BigDecimal.TEN, Instant.now().plusSeconds(3600), "PUBLIC"));
		var submitted = demandService.submit(created.id(), owner, created.version());
		var task = audits.findById(submitted.auditTaskId()).orElseThrow();

		service.decideAuditTask(task.publicId(), UUID.randomUUID(),
				new AdminApplicationService.AuditDecisionCommand("REJECTED", "INCOMPLETE", null, task.version()));

		assertEquals("REJECTED", demands.findById(created.id()).orElseThrow().state().name());
	}

	@Test
	void approvedDemandIsPublishedOnlyAfterFreePriceConfirmation() {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var demandService = new DemandApplicationService(demands, new InMemoryProposalRepository(),
				new InMemoryLineageRepository(), audits);
		var created = demandService.create(owner, new DemandApplicationService.DemandCommand("Need", "Description",
				BigDecimal.ZERO, BigDecimal.TEN, Instant.now().plusSeconds(3600), "PUBLIC"));
		var submitted = demandService.submit(created.id(), owner, created.version());
		var task = audits.findById(submitted.auditTaskId()).orElseThrow();
		service.decideAuditTask(task.publicId(), reviewer,
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, task.version()));
		assertEquals("APPROVED", demands.findById(created.id()).orElseThrow().state().name());

		service.confirmPrice(reviewer, "DEMAND", created.id(),
				new AdminApplicationService.PriceCommand("FREE", BigDecimal.ZERO, Instant.now(), "approved", 0));

		assertEquals("PUBLISHED", demands.findById(created.id()).orElseThrow().state().name());
	}

	@Test
	void staleAuditTargetVersionDoesNotFinalizeTask() {
		var owner = UUID.randomUUID();
		var content = new ContentApplicationService(articles, posts, audits);
		var created = content.create(owner,
				new ContentApplicationService.ContentCommand("DOCUMENT", "PUBLIC", "Title", "Summary", "Body"));
		var submitted = content.submit(created.id(), owner, created.version());
		var task = audits.findById(submitted.auditTaskId()).orElseThrow();
		var article = articles.findById(created.id()).orElseThrow();
		article.applyReview(task.targetVersionId(), true);
		articles.save(article);

		var error = assertThrows(DomainException.class, () -> service.decideAuditTask(task.publicId(),
				UUID.randomUUID(),
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, task.version())));

		assertEquals("AUDIT_TARGET_VERSION_CONFLICT", error.code());
		assertEquals("PENDING", audits.findById(task.publicId()).orElseThrow().state().name());
	}

	@Test
	void priceConfirmationUsesAggregateAndExpectedVersion() {
		var target = UUID.randomUUID();
		var operator = UUID.randomUUID();
		var created = service.confirmPrice(operator, "DOCUMENT", target,
				new AdminApplicationService.PriceCommand("FREE", BigDecimal.ZERO, Instant.now(), "initial", 0));
		assertEquals("0.00", created.amount().amount());
		assertThrows(DomainException.class, () -> service.confirmPrice(operator, "DOCUMENT", target,
				new AdminApplicationService.PriceCommand("FREE", BigDecimal.ZERO, Instant.now(), "stale", 0)));
	}

	@Test
	void v1RejectsPaidPricingMode() {
		var error = assertThrows(DomainException.class,
				() -> service.confirmPrice(UUID.randomUUID(), "DOCUMENT", UUID.randomUUID(),
						new AdminApplicationService.PriceCommand("PAID", new BigDecimal("99.00"), Instant.now(),
								"paid disabled", 0)));
		assertEquals("FEATURE_DISABLED", error.code());
	}

	@Test
	void configUpdateIsVersioned() {
		var actor = UUID.randomUUID();
		var created = service.updateConfig("content.max_tags", actor,
				new AdminApplicationService.ConfigCommand(10, 0, "initial"));
		assertEquals(1, created.version());
		assertThrows(DomainException.class, () -> service.updateConfig("content.max_tags", actor,
				new AdminApplicationService.ConfigCommand(12, 0, "stale")));
	}

	@Test
	void aiTaskIsExplicitlyQueuedAndCannotBeDecidedBeforeOutputExists() {
		var created = service.createAiSuggestion(new AdminApplicationService.AiSuggestionCommand("SUMMARY", "CONTENT",
				UUID.randomUUID(), UUID.randomUUID()));
		assertEquals("QUEUED", created.state());
		assertThrows(DomainException.class, () -> service.decideAiSuggestion(created.id(), UUID.randomUUID(),
				new AdminApplicationService.AiDecisionCommand("ACCEPT", Map.of(), 0)));
	}
}
