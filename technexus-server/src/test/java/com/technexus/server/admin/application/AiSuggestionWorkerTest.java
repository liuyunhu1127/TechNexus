package com.technexus.server.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.server.admin.application.port.OperationsRepository.AiSuggestionRecord;
import com.technexus.server.admin.infrastructure.InMemoryOperationsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiSuggestionWorkerTest {
	private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

	@Test
	void completesSuggestionWithProviderOutput() {
		var operations = new InMemoryOperationsRepository();
		var task = task();
		operations.addSuggestion(task);
		var worker = new AiSuggestionWorker(operations, (kind, type, id, version) -> Map.of("summary", "advice"),
				Clock.fixed(NOW, ZoneOffset.UTC));
		assertTrue(worker.processNext());
		var completed = operations.findSuggestion(task.id()).orElseThrow();
		assertEquals("SUCCEEDED", completed.state());
		assertEquals("advice", completed.output().get("summary"));
		assertFalse(worker.processNext());
	}

	@Test
	void retriesProviderFailuresAndFailsAfterFifthAttempt() {
		var operations = new InMemoryOperationsRepository();
		var task = task();
		operations.addSuggestion(task);
		var worker = new AiSuggestionWorker(operations, (kind, type, id, version) -> {
			throw new IllegalStateException("provider unavailable");
		}, Clock.fixed(NOW, ZoneOffset.UTC));
		for (int attempt = 1; attempt <= 5; attempt++)
			assertTrue(worker.processNext());
		assertEquals("FAILED", operations.findSuggestion(task.id()).orElseThrow().state());
		assertFalse(worker.processNext());
	}

	@Test
	void emptyProviderOutputIsRetried() {
		var operations = new InMemoryOperationsRepository();
		var task = task();
		operations.addSuggestion(task);
		var worker = new AiSuggestionWorker(operations, (kind, type, id, version) -> Map.of(),
				Clock.fixed(NOW, ZoneOffset.UTC));
		assertTrue(worker.processNext());
		assertEquals("QUEUED", operations.findSuggestion(task.id()).orElseThrow().state());
	}

	private static AiSuggestionRecord task() {
		return new AiSuggestionRecord(UUID.randomUUID(), "SUMMARY", "CONTENT", UUID.randomUUID(), UUID.randomUUID(),
				"QUEUED", true, Map.of(), 0);
	}
}
