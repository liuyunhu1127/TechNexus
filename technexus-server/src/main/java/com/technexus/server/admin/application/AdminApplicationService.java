package com.technexus.server.admin.application;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditState;
import com.technexus.audit.domain.AuditTask;
import com.technexus.audit.domain.Decision;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.UuidV7;
import com.technexus.content.api.ArticleRepository;
import com.technexus.content.api.PostRepository;
import com.technexus.demand.api.DemandRepository;
import com.technexus.pricing.api.PriceObjectRepository;
import com.technexus.pricing.domain.PriceObject;
import com.technexus.pricing.domain.PricingMode;
import com.technexus.server.admin.application.port.OperationsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Transactional
public class AdminApplicationService {
	private static final Pattern CONFIG_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{2,127}$");
	private static final Set<String> TARGET_TYPES = Set.of("DOCUMENT", "ATTACHMENT", "DEMAND", "SERVICE");
	private static final Set<String> AI_KINDS = Set.of("TAGS", "SUMMARY", "CATEGORY", "DEMAND_STRUCTURE",
			"AUDIT_REASON");
	private static final Set<String> AI_TARGETS = Set.of("CONTENT", "DEMAND", "AUDIT_TASK");
	private static final Set<String> AI_STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "ACCEPTED",
			"EDITED", "REJECTED");
	private static final Pattern AUDIT_ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{2,95}$");
	private static final String CATEGORY_CONFIG_KEY = "homepage.core_categories";
	private static final String RECOMMENDATION_CONFIG_KEY = "discovery.pinned_items";
	private final AuditTaskRepository audits;
	private final PriceObjectRepository prices;
	private final OperationsRepository operations;
	private final ArticleRepository articles;
	private final PostRepository posts;
	private final DemandRepository demands;
	private final Clock clock;

	@Autowired
	public AdminApplicationService(AuditTaskRepository audits, PriceObjectRepository prices,
			OperationsRepository operations, ArticleRepository articles, PostRepository posts,
			DemandRepository demands) {
		this(audits, prices, operations, articles, posts, demands, Clock.systemUTC());
	}

	AdminApplicationService(AuditTaskRepository audits, PriceObjectRepository prices, OperationsRepository operations,
			ArticleRepository articles, PostRepository posts, DemandRepository demands, Clock clock) {
		this.audits = audits;
		this.prices = prices;
		this.operations = operations;
		this.articles = articles;
		this.posts = posts;
		this.demands = demands;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<AuditTaskView> listAuditTasks(String state) {
		AuditState filter = state == null || state.isBlank() ? null : auditState(state);
		return audits.list(filter, 100).stream().map(AdminApplicationService::view).toList();
	}

	public AuditTaskView decideAuditTask(UUID taskId, UUID reviewerId, AuditDecisionCommand command) {
		var task = audits.findById(taskId).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "审核任务不存在"));
		var expected = task.version();
		requireVersion(expected, command.expectedTaskVersion(), "审核任务版本已变化");
		var finalDecision = decision(command.decision());
		validateDecision(task, reviewerId, command);
		if (task.state() == AuditState.CREATED)
			task.submit();
		if (task.state() == AuditState.PENDING)
			task.claim(reviewerId);
		applyTargetReview(task, finalDecision == Decision.APPROVE, reviewerId);
		task.decide(reviewerId, finalDecision, command.reasonCode(), command.note(), clock.instant());
		audits.save(task, expected);
		return view(task);
	}

	private void applyTargetReview(AuditTask task, boolean approved, UUID reviewerId) {
		switch (task.target().type()) {
			case "CONTENT" -> {
				var article = articles.findById(task.target().publicId());
				if (article.isPresent()) {
					article.get().applyReview(task.targetVersionId(), approved);
					articles.save(article.get());
					return;
				}
				var post = posts.findById(task.target().publicId())
						.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "审核目标不存在"));
				post.applyReview(task.targetVersionId(), approved);
				posts.save(post);
			}
			case "DEMAND" -> {
				var demand = demands.findById(task.target().publicId())
						.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "审核目标不存在"));
				demand.applyReview(task.targetVersionId(), approved);
				demands.save(demand, reviewerId, approved ? "AUDIT_APPROVED" : "AUDIT_REJECTED");
			}
			default -> throw new DomainException("AUDIT_TARGET_TYPE_INVALID", "审核目标类型无效");
		}
	}

	private static void validateDecision(AuditTask task, UUID reviewerId, AuditDecisionCommand command) {
		if (task.state() != AuditState.CREATED && task.state() != AuditState.PENDING
				&& task.state() != AuditState.PROCESSING)
			throw new DomainException("AUDIT_TRANSITION_INVALID", "审核状态转换无效");
		if (task.state() == AuditState.PROCESSING && !reviewerId.equals(task.assigneeId()))
			throw new DomainException("AUDIT_FORBIDDEN", "只有领取人可决定");
		if ("OTHER".equals(command.reasonCode()) && (command.note() == null || command.note().isBlank()))
			throw new DomainException("AUDIT_NOTE_REQUIRED", "OTHER 原因必须说明");
	}

	public PriceView confirmPrice(UUID operatorId, String targetType, UUID targetId, PriceCommand command) {
		if (!TARGET_TYPES.contains(targetType))
			throw new DomainException("TARGET_TYPE_INVALID", "定价目标类型无效");
		var target = new TargetRef(targetType, targetId);
		var existing = prices.findByTarget(target);
		PriceObject price;
		long expected;
		if (existing.isPresent()) {
			price = existing.get();
			expected = price.version();
			requireVersion(expected, command.expectedVersion(), "定价版本已变化");
		} else {
			if (command.expectedVersion() != 0)
				throw new DomainException("VERSION_CONFLICT", "定价版本已变化");
			price = new PriceObject(UuidV7.generate(), target);
			expected = 0;
		}
		var mode = pricingMode(command.mode());
		if (mode != PricingMode.FREE)
			throw new DomainException("FEATURE_DISABLED", "V1 仅启用免费模式");
		if (requireAmount(command.amount()).signum() != 0) {
			throw new DomainException("FREE_PRICE_HAS_AMOUNT", "免费模式金额必须为 0.00");
		}
		price.confirm(mode, null, command.validFrom(), operatorId, command.reason(), clock.instant());
		if (existing.isPresent())
			prices.save(price, expected);
		else
			prices.add(price);
		publishApprovedDemand(target, operatorId);
		return priceView(price);
	}

	private void publishApprovedDemand(TargetRef target, UUID operatorId) {
		if (!"DEMAND".equals(target.type()))
			return;
		var demand = demands.findById(target.publicId())
				.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "定价目标不存在"));
		demand.publish();
		demands.save(demand, operatorId, "PRICE_CONFIRMED");
	}

	@Transactional(readOnly = true)
	public ConfigView getConfig(String key) {
		requireConfigKey(key);
		return operations.findConfig(key).map(AdminApplicationService::configView)
				.orElseThrow(() -> new DomainException("CONFIG_NOT_FOUND", "配置不存在"));
	}

	public ConfigView updateConfig(String key, UUID actorId, ConfigCommand command) {
		requireConfigKey(key);
		if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 1000) {
			throw new DomainException("CONFIG_REASON_INVALID", "配置变更原因无效");
		}
		return configView(
				operations.saveConfig(key, command.value(), command.expectedVersion(), actorId, command.reason()));
	}

	@Transactional(readOnly = true)
	public CategoryConfigView getCategoryConfig() {
		return operations.findConfig(CATEGORY_CONFIG_KEY)
				.map(value -> new CategoryConfigView(convertCategories(value.value()), value.version()))
				.orElseGet(() -> new CategoryConfigView(
						List.of(new CategoryItem("ai-application-engineering", "AI 应用工程", true, 1),
								new CategoryItem("java-spring-backend", "Java 与 Spring 后端工程", true, 2),
								new CategoryItem("deployment-self-hosting", "部署与自托管", true, 3)),
						0));
	}

	public CategoryConfigView updateCategoryConfig(UUID actorId, CategoryConfigCommand command) {
		requireChangeReason(command.reason(), "分类配置变更原因无效");
		validateCategories(command.categories());
		var saved = operations.saveConfig(CATEGORY_CONFIG_KEY, command.categories(), command.expectedVersion(), actorId,
				command.reason());
		return new CategoryConfigView(convertCategories(saved.value()), saved.version());
	}

	@Transactional(readOnly = true)
	public RecommendationConfigView getRecommendationConfig() {
		return operations.findConfig(RECOMMENDATION_CONFIG_KEY)
				.map(value -> new RecommendationConfigView(convertRecommendations(value.value()), value.version()))
				.orElseGet(() -> new RecommendationConfigView(List.of(), 0));
	}

	public RecommendationConfigView updateRecommendationConfig(UUID actorId, RecommendationConfigCommand command) {
		requireChangeReason(command.reason(), "推荐配置变更原因无效");
		validateRecommendations(command.items());
		var saved = operations.saveConfig(RECOMMENDATION_CONFIG_KEY, command.items(), command.expectedVersion(),
				actorId, command.reason());
		return new RecommendationConfigView(convertRecommendations(saved.value()), saved.version());
	}

	@Transactional(readOnly = true)
	public List<FeatureFlagView> listFeatureFlags() {
		return operations.listFeatureFlags().stream().map(AdminApplicationService::featureFlagView).toList();
	}

	@Transactional(readOnly = true)
	public FeatureFlagView getFeatureFlag(String key) {
		requireConfigKey(key);
		return operations.findFeatureFlag(key).map(AdminApplicationService::featureFlagView)
				.orElseThrow(() -> new DomainException("FEATURE_FLAG_NOT_FOUND", "Feature Flag 不存在"));
	}

	public FeatureFlagView updateFeatureFlag(String key, UUID actorId, FeatureFlagCommand command) {
		requireConfigKey(key);
		if (command.reason() == null || command.reason().isBlank() || command.reason().length() > 1000)
			throw new DomainException("CONFIG_REASON_INVALID", "Feature Flag 变更原因无效");
		return featureFlagView(operations.saveFeatureFlag(key, command.enabled(), command.rule(),
				command.expectedVersion(), actorId, command.reason()));
	}

	public AiSuggestionView createAiSuggestion(AiSuggestionCommand command) {
		if (!AI_KINDS.contains(command.kind()))
			throw new DomainException("AI_KIND_INVALID", "AI 建议类型无效");
		if (!AI_TARGETS.contains(command.targetType()))
			throw new DomainException("TARGET_TYPE_INVALID", "AI 目标类型无效");
		var created = operations.addSuggestion(
				new OperationsRepository.AiSuggestionRecord(UuidV7.generate(), command.kind(), command.targetType(),
						command.targetId(), command.targetVersionId(), "QUEUED", true, Map.of(), 0));
		return aiView(created);
	}

	@Transactional(readOnly = true)
	public List<AiSuggestionView> listAiSuggestions(String state) {
		var filter = state == null || state.isBlank() ? null : state;
		if (filter != null && !AI_STATES.contains(filter))
			throw new DomainException("AI_STATE_INVALID", "AI 建议状态无效");
		return operations.listSuggestions(filter, 100).stream().map(AdminApplicationService::aiView).toList();
	}

	public AiSuggestionView decideAiSuggestion(UUID suggestionId, UUID actorId, AiDecisionCommand command) {
		if (!Set.of("ACCEPT", "EDIT", "REJECT").contains(command.decision())) {
			throw new DomainException("AI_DECISION_INVALID", "AI 建议决定无效");
		}
		if ("EDIT".equals(command.decision()) && (command.editedOutput() == null || command.editedOutput().isEmpty())) {
			throw new DomainException("AI_EDIT_REQUIRED", "编辑决定必须提供 editedOutput");
		}
		return aiView(operations.decideSuggestion(suggestionId, command.decision(), command.editedOutput(),
				command.expectedVersion(), actorId));
	}

	@Transactional(readOnly = true)
	public List<OperationAuditView> listOperationAudits(String action) {
		var filter = action == null || action.isBlank() ? null : action;
		if (filter != null && !AUDIT_ACTION.matcher(filter).matches())
			throw new DomainException("OPERATION_AUDIT_ACTION_INVALID", "操作审计类型无效");
		return operations.listOperationAudits(filter, 100).stream().map(AdminApplicationService::operationAuditView)
				.toList();
	}

	@Transactional(readOnly = true)
	public OperationsRepository.DashboardRecord dashboard() {
		return operations.dashboard();
	}

	private static AuditTaskView view(AuditTask task) {
		return new AuditTaskView(task.publicId(), task.target().type(), task.target().publicId(),
				task.targetVersionId(), task.state().name(), task.version());
	}
	private static PriceView priceView(PriceObject price) {
		var amount = price.amount() == null
				? new ApiMoney("0.00", "CNY")
				: new ApiMoney(price.amount().amount().toPlainString(), price.amount().currency().getCurrencyCode());
		return new PriceView(price.target().type(), price.target().publicId(), price.mode().name(), amount,
				price.validFrom(), price.version());
	}
	private static ConfigView configView(OperationsRepository.ConfigRecord value) {
		return new ConfigView(value.key(), value.value(), value.version());
	}
	private static FeatureFlagView featureFlagView(OperationsRepository.FeatureFlagRecord value) {
		return new FeatureFlagView(value.key(), value.enabled(), value.rule(), value.version());
	}
	private static AiSuggestionView aiView(OperationsRepository.AiSuggestionRecord value) {
		return new AiSuggestionView(value.id(), value.kind(), value.targetType(), value.targetId(),
				value.targetVersionId(), value.state(), true, value.output(), value.version());
	}
	private static OperationAuditView operationAuditView(OperationsRepository.OperationAuditRecord value) {
		return new OperationAuditView(value.id(), value.actorId(), value.action(), value.target(), value.createdAt());
	}
	private static void requireVersion(long actual, long expected, String message) {
		if (actual != expected)
			throw new DomainException("VERSION_CONFLICT", message);
	}
	private static void requireConfigKey(String key) {
		if (key == null || !CONFIG_KEY.matcher(key).matches())
			throw new DomainException("CONFIG_KEY_INVALID", "配置键无效");
	}
	private static void requireChangeReason(String reason, String message) {
		if (reason == null || reason.isBlank() || reason.length() > 1000)
			throw new DomainException("CONFIG_REASON_INVALID", message);
	}
	private static void validateCategories(List<CategoryItem> categories) {
		if (categories == null || categories.isEmpty() || categories.size() > 50)
			throw new DomainException("CATEGORY_CONFIG_INVALID", "分类数量必须为 1 至 50 个");
		var keys = new java.util.HashSet<String>();
		var ranks = new java.util.HashSet<Integer>();
		for (var item : categories) {
			if (item == null || !CONFIG_KEY.matcher(item.key()).matches() || item.label() == null
					|| item.label().isBlank() || item.label().length() > 48 || !keys.add(item.key()))
				throw new DomainException("CATEGORY_CONFIG_INVALID", "分类键、名称或唯一性无效");
			if (item.coreRank() != null
					&& (!item.enabled() || item.coreRank() < 1 || item.coreRank() > 3 || !ranks.add(item.coreRank())))
				throw new DomainException("CATEGORY_CONFIG_INVALID", "核心分类排序无效");
		}
		if (ranks.size() < 2 || ranks.size() > 3)
			throw new DomainException("CATEGORY_CORE_COUNT_INVALID", "首页必须突出 2 至 3 个启用分类");
	}
	private static void validateRecommendations(List<RecommendationItem> items) {
		if (items == null || items.size() > 50)
			throw new DomainException("RECOMMENDATION_CONFIG_INVALID", "置顶推荐不能超过 50 个");
		var targets = new java.util.HashSet<String>();
		var ranks = new java.util.HashSet<Integer>();
		for (var item : items) {
			if (item == null || !Set.of("CONTENT", "DEMAND").contains(item.targetType()) || item.targetId() == null
					|| item.rank() < 1 || item.rank() > 50 || !ranks.add(item.rank())
					|| !targets.add(item.targetType() + ":" + item.targetId()))
				throw new DomainException("RECOMMENDATION_CONFIG_INVALID", "推荐目标或排序无效");
		}
	}
	private static List<CategoryItem> convertCategories(Object value) {
		return ((List<?>) value).stream().map(item -> {
			if (item instanceof CategoryItem category)
				return category;
			var map = (Map<?, ?>) item;
			return new CategoryItem((String) map.get("key"), (String) map.get("label"), (Boolean) map.get("enabled"),
					map.get("coreRank") == null ? null : ((Number) map.get("coreRank")).intValue());
		}).toList();
	}
	private static List<RecommendationItem> convertRecommendations(Object value) {
		return ((List<?>) value).stream().map(item -> {
			if (item instanceof RecommendationItem recommendation)
				return recommendation;
			var map = (Map<?, ?>) item;
			return new RecommendationItem((String) map.get("targetType"), UUID.fromString((String) map.get("targetId")),
					((Number) map.get("rank")).intValue());
		}).toList();
	}
	private static BigDecimal requireAmount(BigDecimal amount) {
		if (amount == null)
			throw new DomainException("PRICE_REQUIRED", "收费模式必须有金额");
		return amount;
	}
	private static AuditState auditState(String value) {
		try {
			return AuditState.valueOf(value);
		} catch (IllegalArgumentException error) {
			throw new DomainException("AUDIT_STATE_INVALID", "审核状态无效");
		}
	}
	private static Decision decision(String value) {
		return switch (value) {
			case "APPROVED" -> Decision.APPROVE;
			case "REJECTED" -> Decision.REJECT;
			default -> throw new DomainException("AUDIT_DECISION_INVALID", "审核决定无效");
		};
	}
	private static PricingMode pricingMode(String value) {
		try {
			return PricingMode.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException error) {
			throw new DomainException("PRICING_MODE_INVALID", "定价模式无效");
		}
	}

	public record AuditDecisionCommand(String decision, String reasonCode, String note, long expectedTaskVersion) {
	}
	public record AuditTaskView(UUID id, String targetType, UUID targetId, UUID targetVersionId, String state,
			long version) {
	}
	public record PriceCommand(String mode, BigDecimal amount, Instant validFrom, String reason, long expectedVersion) {
	}
	public record ApiMoney(String amount, String currency) {
	}
	public record PriceView(String targetType, UUID targetId, String mode, ApiMoney amount, Instant validFrom,
			long version) {
	}
	public record ConfigCommand(Object value, long expectedVersion, String reason) {
	}
	public record ConfigView(String key, Object value, long version) {
	}
	public record CategoryItem(String key, String label, boolean enabled, Integer coreRank) {
	}
	public record CategoryConfigCommand(List<CategoryItem> categories, long expectedVersion, String reason) {
	}
	public record CategoryConfigView(List<CategoryItem> categories, long version) {
	}
	public record RecommendationItem(String targetType, UUID targetId, int rank) {
	}
	public record RecommendationConfigCommand(List<RecommendationItem> items, long expectedVersion, String reason) {
	}
	public record RecommendationConfigView(List<RecommendationItem> items, long version) {
	}
	public record FeatureFlagCommand(boolean enabled, Object rule, long expectedVersion, String reason) {
	}
	public record FeatureFlagView(String key, boolean enabled, Object rule, long version) {
	}
	public record AiSuggestionCommand(String kind, String targetType, UUID targetId, UUID targetVersionId) {
	}
	public record AiDecisionCommand(String decision, Map<String, Object> editedOutput, long expectedVersion) {
	}
	public record AiSuggestionView(UUID id, String kind, String targetType, UUID targetId, UUID targetVersionId,
			String state, boolean isSuggestion, Map<String, Object> output, long version) {
	}
	public record OperationAuditView(long id, UUID actorId, String action, Map<String, Object> target,
			Instant createdAt) {
	}
}
