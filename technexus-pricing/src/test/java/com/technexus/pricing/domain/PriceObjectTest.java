package com.technexus.pricing.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriceObjectTest {
	@Test
	void everyConfirmationAppendsHistory() {
		var price = new PriceObject(new TargetRef("ARTICLE", UUID.randomUUID()));
		price.confirm(PricingMode.PAID, Money.cny(new BigDecimal("99.00")), UUID.randomUUID(), "initial",
				Instant.now());
		assertEquals(1, price.history().size());
	}

	@Test
	void freeModeRejectsAmount() {
		var price = new PriceObject(new TargetRef("POST", UUID.randomUUID()));
		assertThrows(DomainException.class, () -> price.confirm(PricingMode.FREE, Money.cny(BigDecimal.ONE),
				UUID.randomUUID(), "bad", Instant.now()));
	}

	@Test
	void paidModeRequiresAmountAndReason() {
		var price = new PriceObject(UUID.randomUUID(), new TargetRef("DEMAND", UUID.randomUUID()));
		assertThrows(DomainException.class,
				() -> price.confirm(PricingMode.PAID, null, UUID.randomUUID(), "reason", Instant.now()));
		assertThrows(DomainException.class,
				() -> price.confirm(PricingMode.FREE, null, UUID.randomUUID(), " ", Instant.now()));
		assertThrows(DomainException.class,
				() -> price.confirm(PricingMode.FREE, null, UUID.randomUUID(), "x".repeat(1001), Instant.now()));
	}

	@Test
	void restoresHistoryAndSupportsExplicitValidity() {
		var id = UUID.randomUUID();
		var target = new TargetRef("ARTICLE", UUID.randomUUID());
		var restored = PriceObject.restore(id, target, PricingMode.FREE, null, Instant.EPOCH, java.util.List.of(), 4);
		var validFrom = Instant.parse("2026-10-01T00:00:00Z");
		restored.confirm(PricingMode.PAID, Money.cny(new BigDecimal("8.00")), validFrom, UUID.randomUUID(), "launch",
				Instant.now());
		assertEquals(validFrom, restored.validFrom());
		assertEquals(5, restored.version());
		assertEquals(id, restored.publicId());
	}
}
