package com.technexus.audit.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditResult(Decision decision, String reasonCode, String note, UUID reviewerId, Instant decidedAt) {
	public AuditResult {
		Objects.requireNonNull(decision);
		Objects.requireNonNull(reasonCode);
		Objects.requireNonNull(reviewerId);
		Objects.requireNonNull(decidedAt);
	}
}
