package com.technexus.demand.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.Visibility;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposalAndLineageTest {
	@Test
	void proposalLifecycleEnforcesProviderDemandAndOwner() {
		var owner = UUID.randomUUID();
		var provider = UUID.randomUUID();
		var demand = new Demand(UUID.randomUUID(), owner, "Need", "Details", null, null, Visibility.PUBLIC);
		var proposal = proposal(demand.publicId(), provider);
		assertThrows(DomainException.class, () -> proposal.submit(provider, demand));
		demand.submit(owner);
		demand.approveForPricing();
		demand.publish();
		assertThrows(DomainException.class, () -> proposal.submit(UUID.randomUUID(), demand));
		proposal.submit(provider, demand);
		assertThrows(DomainException.class, () -> proposal.submit(provider, demand));
		assertThrows(DomainException.class, () -> proposal.accept(UUID.randomUUID(), demand));
		proposal.accept(owner, demand);
		assertEquals(ProposalState.ACCEPTED, proposal.state());
	}

	@Test
	void proposalValidatesInputsAndRestores() {
		var demandId = UUID.randomUUID();
		var provider = UUID.randomUUID();
		assertThrows(DomainException.class,
				() -> new Proposal(UUID.randomUUID(), demandId, provider, " ", List.of("Java"), 1, money()));
		assertThrows(DomainException.class,
				() -> new Proposal(UUID.randomUUID(), demandId, provider, "plan", List.of(), 1, money()));
		assertThrows(DomainException.class,
				() -> new Proposal(UUID.randomUUID(), demandId, provider, "plan", List.of(" "), 1, money()));
		assertThrows(DomainException.class,
				() -> new Proposal(UUID.randomUUID(), demandId, provider, "plan", List.of("Java"), 0, money()));
		assertThrows(DomainException.class,
				() -> new Proposal(UUID.randomUUID(), demandId, provider, "plan", List.of("Java"), 3651, money()));
		var restored = Proposal.restore(UUID.randomUUID(), demandId, provider, "plan", List.of("Java"), 5, money(),
				ProposalState.SUBMITTED, 6);
		assertEquals(6, restored.version());
		assertEquals(5, restored.estimatedDays());
	}

	@Test
	void lineageRejectsSelfLoop() {
		var target = new TargetRef("DEMAND", UUID.randomUUID());
		assertThrows(DomainException.class,
				() -> new Lineage(UUID.randomUUID(), target, target, LineageType.PROBLEM_TO_DEMAND));
		var lineage = new Lineage(UUID.randomUUID(), target, new TargetRef("ARTICLE", UUID.randomUUID()),
				LineageType.DEMAND_TO_SOLUTION);
		assertEquals(LineageType.DEMAND_TO_SOLUTION, lineage.type());
	}

	private static Proposal proposal(UUID demandId, UUID provider) {
		return new Proposal(UUID.randomUUID(), demandId, provider, "plan", List.of("Java"), 5, money());
	}

	private static Money money() {
		return Money.cny(new BigDecimal("100.00"));
	}
}
