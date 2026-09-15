package com.technexus.demand.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Proposal extends AggregateRoot {
	private final UUID publicId;
	private final UUID demandId;
	private final UUID providerId;
	private final String plan;
	private final List<String> techStack;
	private final int estimatedDays;
	private final Money quote;
	private ProposalState state = ProposalState.DRAFT;

	public Proposal(UUID publicId, UUID demandId, UUID providerId, String plan, List<String> techStack,
			int estimatedDays, Money quote) {
		this.publicId = Objects.requireNonNull(publicId);
		this.demandId = Objects.requireNonNull(demandId);
		this.providerId = Objects.requireNonNull(providerId);
		this.plan = Objects.requireNonNull(plan).trim();
		if (this.plan.isEmpty() || this.plan.length() > 50_000)
			throw new DomainException("PROPOSAL_PLAN_REQUIRED", "方案内容无效");
		this.techStack = List.copyOf(Objects.requireNonNull(techStack));
		if (this.techStack.isEmpty() || this.techStack.size() > 30
				|| this.techStack.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 80)) {
			throw new DomainException("PROPOSAL_TECH_STACK_INVALID", "技术栈无效");
		}
		if (estimatedDays < 1 || estimatedDays > 3650)
			throw new DomainException("PROPOSAL_DAYS_INVALID", "预计周期无效");
		this.estimatedDays = estimatedDays;
		this.quote = Objects.requireNonNull(quote);
	}

	public static Proposal restore(UUID publicId, UUID demandId, UUID providerId, String plan, List<String> techStack,
			int estimatedDays, Money quote, ProposalState state, long version) {
		var proposal = new Proposal(publicId, demandId, providerId, plan, techStack, estimatedDays, quote);
		proposal.state = Objects.requireNonNull(state);
		proposal.restoreVersion(version);
		return proposal;
	}

	public void submit(UUID actorId, Demand demand) {
		requireProvider(actorId);
		if (!demand.publicId().equals(demandId) || !demand.acceptsProposals())
			throw new DomainException("DEMAND_NOT_ACCEPTING_PROPOSALS", "需求不接受方案");
		if (state != ProposalState.DRAFT)
			throw new DomainException("PROPOSAL_TRANSITION_INVALID", "方案状态无效");
		state = ProposalState.SUBMITTED;
		changed();
	}

	public void accept(UUID actorId, Demand demand) {
		if (!demand.ownerId().equals(actorId) || !demand.publicId().equals(demandId)) {
			throw new DomainException("DEMAND_FORBIDDEN", "只有需求所有者可以确认方案");
		}
		if (state != ProposalState.SUBMITTED)
			throw new DomainException("PROPOSAL_TRANSITION_INVALID", "方案状态无效");
		state = ProposalState.ACCEPTED;
		changed();
	}

	private void requireProvider(UUID actorId) {
		if (!providerId.equals(actorId))
			throw new DomainException("PROPOSAL_FORBIDDEN", "只能由服务者修改方案");
	}

	public UUID publicId() {
		return publicId;
	}
	public UUID demandId() {
		return demandId;
	}
	public UUID providerId() {
		return providerId;
	}
	public String plan() {
		return plan;
	}
	public List<String> techStack() {
		return techStack;
	}
	public int estimatedDays() {
		return estimatedDays;
	}
	public Money quote() {
		return quote;
	}
	public ProposalState state() {
		return state;
	}
}
