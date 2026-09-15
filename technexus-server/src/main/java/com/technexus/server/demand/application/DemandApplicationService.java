package com.technexus.server.demand.application;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditTask;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.UuidV7;
import com.technexus.common.domain.Visibility;
import com.technexus.demand.api.DemandRepository;
import com.technexus.demand.api.ProposalRepository;
import com.technexus.demand.api.LineageRepository;
import com.technexus.demand.domain.BudgetRange;
import com.technexus.demand.domain.Demand;
import com.technexus.demand.domain.DemandState;
import com.technexus.demand.domain.Proposal;
import com.technexus.demand.domain.Lineage;
import com.technexus.demand.domain.LineageType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DemandApplicationService {
	private final DemandRepository demands;
	private final ProposalRepository proposals;
	private final LineageRepository lineages;
	private final AuditTaskRepository audits;

	public DemandApplicationService(DemandRepository demands, ProposalRepository proposals, LineageRepository lineages,
			AuditTaskRepository audits) {
		this.demands = demands;
		this.proposals = proposals;
		this.lineages = lineages;
		this.audits = audits;
	}

	public DemandView create(UUID ownerId, DemandCommand command) {
		var demand = new Demand(UuidV7.generate(), ownerId, command.title(), command.description(), budget(command),
				command.deadlineAt(), visibility(command.visibility()));
		demands.add(demand);
		return view(demand);
	}

	public DemandView createFromContent(UUID contentId, UUID ownerId, DemandCommand command) {
		var result = create(ownerId, command);
		lineages.add(new Lineage(UuidV7.generate(), new TargetRef("CONTENT", contentId),
				new TargetRef("DEMAND", result.id()), LineageType.PROBLEM_TO_DEMAND));
		return result;
	}

	@Transactional(readOnly = true)
	public List<DemandView> listPublic() {
		return demands.listPublic(50).stream().map(DemandApplicationService::view).toList();
	}

	@Transactional(readOnly = true)
	public DemandView get(UUID id, UUID actorId) {
		var demand = require(id);
		var publicState = switch (demand.state()) {
			case PUBLISHED, NEGOTIATING, CONFIRMED, IN_PROGRESS, DELIVERED, COMPLETED -> true;
			default -> false;
		};
		if ((!publicState || demand.visibility() != Visibility.PUBLIC) && !demand.ownerId().equals(actorId)) {
			throw new DomainException("RESOURCE_NOT_FOUND", "需求不存在");
		}
		return view(demand);
	}

	public DemandView update(UUID id, UUID actorId, long expectedVersion, DemandCommand command) {
		var demand = requireVersion(id, expectedVersion);
		demand.update(actorId, command.title() == null ? demand.title() : command.title(),
				command.description() == null ? demand.description() : command.description(),
				command.budgetMin() == null && command.budgetMax() == null ? demand.budget() : budget(command),
				command.deadlineAt() == null ? demand.deadlineAt() : command.deadlineAt(),
				command.visibility() == null ? demand.visibility() : visibility(command.visibility()));
		demands.save(demand, actorId, "UPDATE");
		return view(demand);
	}

	public DemandView submit(UUID id, UUID actorId, long expectedVersion) {
		var demand = requireVersion(id, expectedVersion);
		if (!demand.ownerId().equals(actorId))
			throw new DomainException("DEMAND_FORBIDDEN", "只能由需求所有者修改");
		if (demand.state() == DemandState.PENDING_REVIEW && demand.pendingReviewVersionId() != null) {
			var existing = audits
					.findByTarget(new TargetRef("DEMAND", demand.publicId()), demand.pendingReviewVersionId())
					.orElse(null);
			if (existing != null)
				return withAudit(view(demand), existing.publicId());
		}
		var targetVersionId = demand.submit(actorId);
		demands.save(demand, actorId, "SUBMIT");
		var task = new AuditTask(UuidV7.generate(), new TargetRef("DEMAND", demand.publicId()), targetVersionId);
		task.submit();
		audits.add(task);
		return withAudit(view(demand), task.publicId());
	}

	public ProposalView createProposal(UUID demandId, UUID providerId, ProposalCommand command) {
		var demand = require(demandId);
		if (demand.visibility() != Visibility.PUBLIC && !demand.ownerId().equals(providerId)) {
			throw new DomainException("RESOURCE_NOT_FOUND", "需求不存在");
		}
		var proposal = new Proposal(UuidV7.generate(), demandId, providerId, command.plan(), command.techStack(),
				command.estimatedDays(), Money.cny(command.suggestedQuote()));
		proposal.submit(providerId, demand);
		proposals.add(proposal);
		lineages.add(new Lineage(UuidV7.generate(), new TargetRef("DEMAND", demandId),
				new TargetRef("PROPOSAL", proposal.publicId()), LineageType.DEMAND_TO_PROPOSAL));
		return proposalView(proposal);
	}

	@Transactional(readOnly = true)
	public LineageView getLineage(String targetType, UUID targetId) {
		var target = new TargetRef(targetType, targetId);
		var connected = lineages.findConnected(target);
		var nodes = new LinkedHashMap<String, LineageNode>();
		var edges = connected.stream().map(value -> {
			nodes.putIfAbsent(value.source().type() + value.source().publicId(), node(value.source()));
			nodes.putIfAbsent(value.target().type() + value.target().publicId(), node(value.target()));
			return new LineageEdge(value.source().publicId(), value.target().publicId(), value.type().name());
		}).toList();
		return new LineageView(List.copyOf(nodes.values()), edges);
	}

	public DemandView transition(UUID id, UUID actorId, long expectedVersion, String action, UUID proposalId) {
		var demand = requireVersion(id, expectedVersion);
		switch (action) {
			case "START_NEGOTIATION" -> demand.startNegotiation(actorId);
			case "CONFIRM_PROPOSAL" -> {
				if (proposalId == null)
					throw new DomainException("PROPOSAL_REQUIRED", "确认方案时必须提供 proposalId");
				var proposal = proposals.findById(proposalId)
						.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "方案不存在"));
				if (!proposal.demandId().equals(id))
					throw new DomainException("RESOURCE_NOT_FOUND", "方案不存在");
				demand.confirmProposal(actorId);
				proposal.accept(actorId, demand);
				proposals.save(proposal);
			}
			case "START_WORK" -> demand.start(actorId);
			case "DELIVER" -> demand.deliver(proposals.isAcceptedProvider(id, actorId));
			case "COMPLETE" -> demand.complete(actorId);
			case "CANCEL" -> demand.cancel(actorId);
			case "OFFLINE" -> throw new DomainException("DEMAND_ACTION_FORBIDDEN", "下架只能由管理员执行");
			default -> throw new DomainException("DEMAND_ACTION_UNSUPPORTED", "该需求动作尚不可用");
		}
		demands.save(demand, actorId, action);
		return view(demand);
	}

	private Demand require(UUID id) {
		return demands.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "需求不存在"));
	}
	private Demand requireVersion(UUID id, long expected) {
		var demand = require(id);
		if (demand.version() != expected)
			throw new DomainException("VERSION_CONFLICT", "需求版本已变化");
		return demand;
	}
	private static BudgetRange budget(DemandCommand command) {
		return new BudgetRange(money(command.budgetMin()), money(command.budgetMax()));
	}
	private static Money money(BigDecimal value) {
		return value == null ? null : Money.cny(value);
	}
	private static Visibility visibility(String value) {
		try {
			return Visibility.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException error) {
			throw new DomainException("DEMAND_VISIBILITY_INVALID", "需求可见性无效");
		}
	}
	private static DemandView view(Demand demand) {
		return new DemandView(demand.publicId(), demand.state().name(), demand.title(), demand.description(),
				apiMoney(demand.budget() == null ? null : demand.budget().minimum()),
				apiMoney(demand.budget() == null ? null : demand.budget().maximum()), demand.deadlineAt(),
				demand.visibility().name(), demand.version(), null);
	}
	private static DemandView withAudit(DemandView value, UUID auditTaskId) {
		return new DemandView(value.id(), value.state(), value.title(), value.description(), value.budgetMin(),
				value.budgetMax(), value.deadlineAt(), value.visibility(), value.version(), auditTaskId);
	}
	private static ApiMoney apiMoney(Money money) {
		return money == null ? null : new ApiMoney(money.amount().toPlainString(), money.currency().getCurrencyCode());
	}

	public record DemandCommand(String title, String description, BigDecimal budgetMin, BigDecimal budgetMax,
			Instant deadlineAt, String visibility) {
	}
	public record ProposalCommand(String plan, List<String> techStack, int estimatedDays, BigDecimal suggestedQuote) {
	}
	public record ApiMoney(String amount, String currency) {
	}
	public record DemandView(UUID id, String state, String title, String description, ApiMoney budgetMin,
			ApiMoney budgetMax, Instant deadlineAt, String visibility, long version, UUID auditTaskId) {
	}
	public record ProposalView(UUID id, UUID demandId, String state, int estimatedDays, ApiMoney suggestedQuote,
			long version) {
	}
	public record LineageNode(String type, UUID id, String label) {
	}
	public record LineageEdge(UUID sourceId, UUID targetId, String type) {
	}
	public record LineageView(List<LineageNode> nodes, List<LineageEdge> edges) {
	}
	private static LineageNode node(TargetRef target) {
		return new LineageNode(target.type(), target.publicId(), target.type() + " " + target.publicId());
	}
	private static ProposalView proposalView(Proposal proposal) {
		return new ProposalView(proposal.publicId(), proposal.demandId(), proposal.state().name(),
				proposal.estimatedDays(), apiMoney(proposal.quote()), proposal.version());
	}
}
