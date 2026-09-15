package com.technexus.audit.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AuditTask extends AggregateRoot {
	private final UUID publicId;
	private final TargetRef target;
	private final UUID targetVersionId;
	private AuditState state = AuditState.CREATED;
	private UUID assigneeId;
	private AuditResult result;

	public AuditTask(UUID publicId, TargetRef target, UUID targetVersionId) {
		this.publicId = Objects.requireNonNull(publicId);
		this.target = Objects.requireNonNull(target);
		this.targetVersionId = Objects.requireNonNull(targetVersionId);
	}

	public static AuditTask restore(UUID publicId, TargetRef target, UUID targetVersionId, AuditState state,
			UUID assigneeId, AuditResult result, long version) {
		var task = new AuditTask(publicId, target, targetVersionId);
		task.state = Objects.requireNonNull(state);
		task.assigneeId = assigneeId;
		task.result = result;
		task.restoreVersion(version);
		return task;
	}

	public void submit() {
		requireState(AuditState.CREATED);
		state = AuditState.PENDING;
		changed();
	}

	public void claim(UUID reviewerId) {
		requireState(AuditState.PENDING);
		assigneeId = Objects.requireNonNull(reviewerId);
		state = AuditState.PROCESSING;
		changed();
	}

	public void decide(UUID reviewerId, Decision decision, String reasonCode, String note, Instant now) {
		requireState(AuditState.PROCESSING);
		if (!Objects.equals(assigneeId, reviewerId))
			throw new DomainException("AUDIT_FORBIDDEN", "只有领取人可决定");
		if ("OTHER".equals(reasonCode) && (note == null || note.isBlank()))
			throw new DomainException("AUDIT_NOTE_REQUIRED", "OTHER 原因必须说明");
		result = new AuditResult(decision, reasonCode, note, reviewerId, now);
		state = decision == Decision.APPROVE ? AuditState.APPROVED : AuditState.REJECTED;
		changed();
	}

	private void requireState(AuditState expected) {
		if (state != expected)
			throw new DomainException("AUDIT_TRANSITION_INVALID", "审核状态转换无效");
	}

	public UUID publicId() {
		return publicId;
	}
	public TargetRef target() {
		return target;
	}
	public UUID targetVersionId() {
		return targetVersionId;
	}
	public AuditState state() {
		return state;
	}
	public UUID assigneeId() {
		return assigneeId;
	}
	public AuditResult result() {
		return result;
	}
}
