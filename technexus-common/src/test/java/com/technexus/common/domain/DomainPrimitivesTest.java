package com.technexus.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainPrimitivesTest {
	@Test
	void targetReferenceNormalizesAndValidatesType() {
		var id = UUID.randomUUID();
		assertEquals("ARTICLE", new TargetRef(" article ", id).type());
		assertThrows(DomainException.class, () -> new TargetRef("  ", id));
		assertThrows(NullPointerException.class, () -> new TargetRef(null, id));
		assertThrows(NullPointerException.class, () -> new TargetRef("ARTICLE", null));
	}

	@Test
	void aggregateTracksEventsAndRejectsNegativeRestoredVersion() {
		var aggregate = new TestAggregate();
		aggregate.mutate();
		aggregate.emit();
		assertEquals(1, aggregate.version());
		assertEquals(1, aggregate.pullDomainEvents().size());
		assertTrue(aggregate.pullDomainEvents().isEmpty());
		assertThrows(IllegalArgumentException.class, () -> aggregate.restore(-1));
		aggregate.restore(7);
		assertEquals(7, aggregate.version());
	}

	private static final class TestAggregate extends AggregateRoot {
		void mutate() {
			changed();
		}

		void emit() {
			raise(SimpleDomainEvent.now("Changed", java.util.Map.of("ok", true)));
		}

		void restore(long version) {
			restoreVersion(version);
		}
	}
}
