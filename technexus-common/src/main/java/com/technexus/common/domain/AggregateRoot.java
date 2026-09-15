package com.technexus.common.domain;

import java.util.ArrayList;
import java.util.List;

public abstract class AggregateRoot {
	private final List<DomainEvent> domainEvents = new ArrayList<>();
	private long version;

	protected final void raise(DomainEvent event) {
		domainEvents.add(event);
	}

	public final List<DomainEvent> pullDomainEvents() {
		var result = List.copyOf(domainEvents);
		domainEvents.clear();
		return result;
	}

	public final long version() {
		return version;
	}

	protected final void changed() {
		version++;
	}

	protected final void restoreVersion(long value) {
		if (value < 0)
			throw new IllegalArgumentException("Aggregate version cannot be negative");
		version = value;
	}
}
