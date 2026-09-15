package com.technexus.audit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditTaskTest {
	@Test
	void decisionIsBoundToClaimedReviewerAndFinal() {
		var reviewer = UUID.randomUUID();
		var task = new AuditTask(UUID.randomUUID(), new TargetRef("ARTICLE", UUID.randomUUID()), UUID.randomUUID());
		task.submit();
		task.claim(reviewer);
		task.decide(reviewer, Decision.APPROVE, "VALID", null, Instant.now());
		assertEquals(AuditState.APPROVED, task.state());
		assertThrows(DomainException.class,
				() -> task.decide(reviewer, Decision.REJECT, "OTHER", "late", Instant.now()));
	}

	@Test
	void enforcesLifecycleReviewerAndOtherNote() {
		var reviewer = UUID.randomUUID();
		var task = new AuditTask(UUID.randomUUID(), new TargetRef("DEMAND", UUID.randomUUID()), UUID.randomUUID());
		assertThrows(DomainException.class, () -> task.claim(reviewer));
		task.submit();
		assertThrows(DomainException.class, task::submit);
		task.claim(reviewer);
		assertThrows(DomainException.class,
				() -> task.decide(UUID.randomUUID(), Decision.REJECT, "ILLEGAL", null, Instant.now()));
		assertThrows(DomainException.class, () -> task.decide(reviewer, Decision.REJECT, "OTHER", " ", Instant.now()));
		task.decide(reviewer, Decision.REJECT, "OTHER", "details", Instant.now());
		assertEquals(AuditState.REJECTED, task.state());
		assertEquals(Decision.REJECT, task.result().decision());
	}

	@Test
	void restoresPersistedTask() {
		var reviewer = UUID.randomUUID();
		var result = new AuditResult(Decision.APPROVE, "VALID", null, reviewer, Instant.EPOCH);
		var task = AuditTask.restore(UUID.randomUUID(), new TargetRef("ARTICLE", UUID.randomUUID()), UUID.randomUUID(),
				AuditState.APPROVED, reviewer, result, 8);
		assertEquals(8, task.version());
		assertEquals(result, task.result());
	}
}
