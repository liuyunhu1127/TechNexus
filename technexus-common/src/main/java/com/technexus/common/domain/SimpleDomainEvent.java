package com.technexus.common.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SimpleDomainEvent(UUID eventId, Instant occurredAt, String type, int schemaVersion,
		Map<String, Object> payload) implements DomainEvent {
	public SimpleDomainEvent {
		payload = Map.copyOf(payload);
	}

	public static SimpleDomainEvent now(String type, Map<String, Object> payload) {
		return new SimpleDomainEvent(UuidV7.generate(), Instant.now(), type, 1, payload);
	}
}
