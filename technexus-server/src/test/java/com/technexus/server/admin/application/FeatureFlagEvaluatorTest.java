package com.technexus.server.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.infrastructure.InMemoryOperationsRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureFlagEvaluatorTest {
	@Test
	void appliesOffAndDeterministicPercentageRules() {
		var operations = new InMemoryOperationsRepository();
		var evaluator = new FeatureFlagEvaluator(operations);
		var actor = UUID.randomUUID();
		operations.saveFeatureFlag("registration.enabled", false, Map.of("percentage", 100), 0, actor, "off");
		assertFalse(evaluator.enabled("registration.enabled", "a@example.com"));
		var error = assertThrows(DomainException.class,
				() -> evaluator.requireEnabled("registration.enabled", "a@example.com"));
		assertEquals("FEATURE_DISABLED", error.code());
		operations.saveFeatureFlag("registration.enabled", true, Map.of("percentage", 25), 1, actor, "gray");
		assertEquals(evaluator.enabled("registration.enabled", "stable-subject"),
				evaluator.enabled("registration.enabled", "stable-subject"));
	}
}
