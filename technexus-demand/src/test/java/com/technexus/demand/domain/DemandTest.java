package com.technexus.demand.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.Visibility;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DemandTest {
	@Test
	void onlyPublishedDemandAcceptsProposals() {
		var owner = UUID.randomUUID();
		var demand = demand(owner);
		assertFalse(demand.acceptsProposals());
		demand.submit(owner);
		demand.approveForPricing();
		demand.publish();
		assertTrue(demand.acceptsProposals());
		demand.startNegotiation(owner);
		assertFalse(demand.acceptsProposals());
	}

	@Test
	void budgetMinimumCannotExceedMaximum() {
		assertThrows(DomainException.class,
				() -> new BudgetRange(Money.cny(new BigDecimal("2.00")), Money.cny(new BigDecimal("1.00"))));
	}

	@Test
	void terminalDemandCannotBeCancelled() {
		var owner = UUID.randomUUID();
		var demand = demand(owner);
		demand.submit(owner);
		demand.approveForPricing();
		demand.publish();
		demand.startNegotiation(owner);
		demand.confirmProposal(owner);
		demand.start(owner);
		demand.deliver(true);
		demand.complete(owner);
		assertThrows(DomainException.class, () -> demand.cancel(owner));
	}

	@Test
	void reviewVersionAndOwnerAreEnforced() {
		var owner = UUID.randomUUID();
		var demand = demand(owner);
		assertThrows(DomainException.class, () -> demand.update(UUID.randomUUID(), "x", "y", null));
		var reviewVersion = demand.submit(owner);
		assertThrows(DomainException.class, () -> demand.applyReview(UUID.randomUUID(), true));
		demand.applyReview(reviewVersion, false);
		assertEquals(DemandState.REJECTED, demand.state());
		demand.update(owner, " Next ", " Updated ", null, null, Visibility.OWNER_ONLY);
		assertEquals(DemandState.DRAFT, demand.state());
		assertEquals("Next", demand.title());
		var nextVersion = demand.submit(owner);
		demand.applyReview(nextVersion, true);
		assertEquals(DemandState.APPROVED, demand.state());
	}

	@Test
	void validatesFieldsRestoreAndDeliveryActor() {
		var owner = UUID.randomUUID();
		assertThrows(DomainException.class,
				() -> new Demand(UUID.randomUUID(), owner, " ", "description", null, null, Visibility.PUBLIC));
		assertThrows(DomainException.class,
				() -> new Demand(UUID.randomUUID(), owner, "title", "x".repeat(50001), null, null, Visibility.PUBLIC));
		assertThrows(DomainException.class, () -> Demand.restore(UUID.randomUUID(), owner, "title", "description", null,
				null, Visibility.PUBLIC, DemandState.PENDING_REVIEW, null, 0));
		var pendingId = UUID.randomUUID();
		var restored = Demand.restore(UUID.randomUUID(), owner, "title", "description", null, null, Visibility.PUBLIC,
				DemandState.PENDING_REVIEW, pendingId, 7);
		assertEquals(7, restored.version());
		restored.applyReview(pendingId, true);
		restored.publish();
		restored.startNegotiation(owner);
		restored.confirmProposal(owner);
		restored.start(owner);
		assertThrows(DomainException.class, () -> restored.deliver(false));
		restored.deliver(true);
	}

	@Test
	void nonTerminalDemandCanBeCancelledOnlyByOwner() {
		var owner = UUID.randomUUID();
		var demand = demand(owner);
		assertThrows(DomainException.class, () -> demand.cancel(UUID.randomUUID()));
		demand.cancel(owner);
		assertEquals(DemandState.CANCELLED, demand.state());
	}

	private static Demand demand(UUID owner) {
		return new Demand(UUID.randomUUID(), owner, "Need", "Description", null, Instant.now().plusSeconds(3600),
				Visibility.PUBLIC);
	}
}
