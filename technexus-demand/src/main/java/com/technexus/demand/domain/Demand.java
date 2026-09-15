package com.technexus.demand.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.SimpleDomainEvent;
import com.technexus.common.domain.UuidV7;
import com.technexus.common.domain.Visibility;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Demand extends AggregateRoot {
	private static final Set<DemandState> TERMINAL = Set.of(DemandState.COMPLETED, DemandState.CANCELLED,
			DemandState.EXPIRED, DemandState.OFFLINE);
	private final UUID publicId;
	private final UUID ownerId;
	private String title;
	private String description;
	private BudgetRange budget;
	private Instant deadlineAt;
	private Visibility visibility;
	private DemandState state = DemandState.DRAFT;
	private UUID pendingReviewVersionId;

	public Demand(UUID publicId, UUID ownerId, String title, String description, BudgetRange budget, Instant deadlineAt,
			Visibility visibility) {
		this.publicId = Objects.requireNonNull(publicId);
		this.ownerId = Objects.requireNonNull(ownerId);
		this.title = requireText(title, 160);
		this.description = requireText(description, 50_000);
		this.budget = budget;
		this.deadlineAt = deadlineAt;
		this.visibility = Objects.requireNonNull(visibility);
	}

	public static Demand restore(UUID publicId, UUID ownerId, String title, String description, BudgetRange budget,
			Instant deadlineAt, Visibility visibility, DemandState state, long version) {
		return restore(publicId, ownerId, title, description, budget, deadlineAt, visibility, state, null, version);
	}

	public static Demand restore(UUID publicId, UUID ownerId, String title, String description, BudgetRange budget,
			Instant deadlineAt, Visibility visibility, DemandState state, UUID pendingReviewVersionId, long version) {
		var demand = new Demand(publicId, ownerId, title, description, budget, deadlineAt, visibility);
		demand.state = Objects.requireNonNull(state);
		demand.pendingReviewVersionId = pendingReviewVersionId;
		if ((state == DemandState.PENDING_REVIEW) != (pendingReviewVersionId != null))
			throw new DomainException("DEMAND_REVIEW_VERSION_INVALID", "需求审核版本与状态不一致");
		demand.restoreVersion(version);
		return demand;
	}

	public void update(UUID actorId, String title, String description, BudgetRange budget) {
		update(actorId, title, description, budget, deadlineAt, visibility);
	}

	public void update(UUID actorId, String title, String description, BudgetRange budget, Instant deadlineAt,
			Visibility visibility) {
		requireOwner(actorId);
		requireState(DemandState.DRAFT, DemandState.REJECTED);
		this.title = requireText(title, 160);
		this.description = requireText(description, 50_000);
		this.budget = budget;
		this.deadlineAt = deadlineAt;
		this.visibility = Objects.requireNonNull(visibility);
		state = DemandState.DRAFT;
		changed();
	}

	public UUID submit(UUID actorId) {
		requireOwner(actorId);
		requireState(DemandState.DRAFT);
		pendingReviewVersionId = UuidV7.generate();
		state = DemandState.PENDING_REVIEW;
		changed();
		raise(SimpleDomainEvent.now("DemandSubmitted",
				Map.of("demandId", publicId.toString(), "versionId", pendingReviewVersionId.toString())));
		return pendingReviewVersionId;
	}

	public void applyReview(UUID reviewedVersionId, boolean approved) {
		if (state != DemandState.PENDING_REVIEW || !Objects.equals(pendingReviewVersionId, reviewedVersionId))
			throw new DomainException("AUDIT_TARGET_VERSION_CONFLICT", "审核目标版本已变化");
		state = approved ? DemandState.APPROVED : DemandState.REJECTED;
		pendingReviewVersionId = null;
		changed();
	}

	public void approveForPricing() {
		requireState(DemandState.PENDING_REVIEW);
		state = DemandState.APPROVED;
		changed();
	}
	public void publish() {
		requireState(DemandState.APPROVED);
		state = DemandState.PUBLISHED;
		changed();
	}

	public void startNegotiation(UUID actorId) {
		requireOwner(actorId);
		requireState(DemandState.PUBLISHED);
		state = DemandState.NEGOTIATING;
		changed();
	}

	public void confirmProposal(UUID actorId) {
		requireOwner(actorId);
		requireState(DemandState.NEGOTIATING);
		state = DemandState.CONFIRMED;
		changed();
	}

	public void start(UUID actorId) {
		requireOwner(actorId);
		requireState(DemandState.CONFIRMED);
		state = DemandState.IN_PROGRESS;
		changed();
	}

	public void deliver(boolean acceptedProvider) {
		if (!acceptedProvider)
			throw new DomainException("PROPOSAL_FORBIDDEN", "只有已确认方案的服务者可以交付");
		requireState(DemandState.IN_PROGRESS);
		state = DemandState.DELIVERED;
		changed();
	}

	public void complete(UUID actorId) {
		requireOwner(actorId);
		requireState(DemandState.DELIVERED);
		state = DemandState.COMPLETED;
		changed();
	}

	public void cancel(UUID actorId) {
		requireOwner(actorId);
		if (TERMINAL.contains(state))
			throw new DomainException("DEMAND_TERMINAL", "终态需求不可取消");
		state = DemandState.CANCELLED;
		changed();
	}

	public boolean acceptsProposals() {
		return state == DemandState.PUBLISHED;
	}

	private void requireOwner(UUID actorId) {
		if (!ownerId.equals(actorId))
			throw new DomainException("DEMAND_FORBIDDEN", "只能由需求所有者修改");
	}

	private void requireState(DemandState... expected) {
		for (var candidate : expected)
			if (state == candidate)
				return;
		throw new DomainException("DEMAND_TRANSITION_INVALID", "需求状态转换无效");
	}

	private static String requireText(String value, int max) {
		var result = Objects.requireNonNull(value).trim();
		if (result.isEmpty() || result.length() > max)
			throw new DomainException("DEMAND_FIELD_INVALID", "需求字段长度无效");
		return result;
	}

	public UUID publicId() {
		return publicId;
	}
	public UUID ownerId() {
		return ownerId;
	}
	public String title() {
		return title;
	}
	public String description() {
		return description;
	}
	public BudgetRange budget() {
		return budget;
	}
	public Instant deadlineAt() {
		return deadlineAt;
	}
	public Visibility visibility() {
		return visibility;
	}
	public DemandState state() {
		return state;
	}
	public UUID pendingReviewVersionId() {
		return pendingReviewVersionId;
	}
}
