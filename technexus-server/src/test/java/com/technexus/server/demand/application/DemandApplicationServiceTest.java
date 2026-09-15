package com.technexus.server.demand.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import com.technexus.demand.domain.Demand;
import com.technexus.demand.domain.DemandState;
import com.technexus.server.demand.infrastructure.InMemoryDemandRepository;
import com.technexus.server.demand.infrastructure.InMemoryProposalRepository;
import com.technexus.server.demand.infrastructure.InMemoryLineageRepository;
import com.technexus.server.admin.infrastructure.InMemoryAuditTaskRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemandApplicationServiceTest {
	private final DemandApplicationService service = new DemandApplicationService(new InMemoryDemandRepository(),
			new InMemoryProposalRepository(), new InMemoryLineageRepository(), new InMemoryAuditTaskRepository());
	private final UUID owner = UUID.randomUUID();

	@Test
	void createsAndSubmitsDemandThroughAggregate() {
		var created = service.create(owner, command("PUBLIC"));

		var submitted = service.submit(created.id(), owner, created.version());

		assertEquals("PENDING_REVIEW", submitted.state());
		assertEquals(1, submitted.version());
		assertNotNull(submitted.auditTaskId());
		assertEquals(submitted.auditTaskId(), service.submit(created.id(), owner, submitted.version()).auditTaskId());
	}

	@Test
	void rejectsStaleVersion() {
		var created = service.create(owner, command("OWNER_ONLY"));

		var error = assertThrows(DomainException.class,
				() -> service.update(created.id(), owner, 8, command("OWNER_ONLY")));

		assertEquals("VERSION_CONFLICT", error.code());
	}

	@Test
	void rejectsOtherOwnerUpdate() {
		var created = service.create(owner, command("OWNER_ONLY"));

		var error = assertThrows(DomainException.class,
				() -> service.update(created.id(), UUID.randomUUID(), 0, command("OWNER_ONLY")));

		assertEquals("DEMAND_FORBIDDEN", error.code());
	}

	@Test
	void validatesBudgetRange() {
		var invalid = new DemandApplicationService.DemandCommand("Need", "Description", new BigDecimal("200.00"),
				new BigDecimal("100.00"), Instant.now().plusSeconds(3600), "PUBLIC");

		var error = assertThrows(DomainException.class, () -> service.create(owner, invalid));

		assertEquals("BUDGET_RANGE_INVALID", error.code());
	}

	@Test
	void createsSourceLineageAtomicallyWithDemandUseCase() {
		var contentId = UUID.randomUUID();

		var demand = service.createFromContent(contentId, owner, command("PUBLIC"));
		var graph = service.getLineage("DEMAND", demand.id());

		assertEquals(2, graph.nodes().size());
		assertEquals("PROBLEM_TO_DEMAND", graph.edges().getFirst().type());
	}

	@Test
	void acceptedProviderCanDeliverConfirmedDemand() {
		var demandRepository = new InMemoryDemandRepository();
		var proposalRepository = new InMemoryProposalRepository();
		var published = Demand.restore(UUID.randomUUID(), owner, "Need", "Description", null,
				Instant.now().plusSeconds(3600), Visibility.PUBLIC, DemandState.PUBLISHED, 4);
		demandRepository.add(published);
		var application = new DemandApplicationService(demandRepository, proposalRepository,
				new InMemoryLineageRepository(), new InMemoryAuditTaskRepository());
		var provider = UUID.randomUUID();
		var proposal = application.createProposal(published.publicId(), provider,
				new DemandApplicationService.ProposalCommand("Plan", List.of("Java"), 10, new BigDecimal("100.00")));

		var negotiating = application.transition(published.publicId(), owner, 4, "START_NEGOTIATION", null);
		var confirmed = application.transition(published.publicId(), owner, negotiating.version(), "CONFIRM_PROPOSAL",
				proposal.id());
		var started = application.transition(published.publicId(), owner, confirmed.version(), "START_WORK", null);
		var delivered = application.transition(published.publicId(), provider, started.version(), "DELIVER", null);

		assertEquals("DELIVERED", delivered.state());
	}

	private static DemandApplicationService.DemandCommand command(String visibility) {
		return new DemandApplicationService.DemandCommand("Need", "Description", new BigDecimal("100.00"),
				new BigDecimal("200.00"), Instant.now().plusSeconds(3600), visibility);
	}
}
