package com.technexus.server.web;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.AdminApplicationService;
import com.technexus.server.admin.application.BatchApplicationService;
import com.technexus.server.admin.application.FeatureFlagEvaluator;
import com.technexus.server.community.application.CommunityApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
	private final AdminApplicationService admin;
	private final CommunityApplicationService community;
	private final BatchApplicationService batches;
	private final FeatureFlagEvaluator flags;

	public AdminController(AdminApplicationService admin, CommunityApplicationService community,
			BatchApplicationService batches, FeatureFlagEvaluator flags) {
		this.admin = admin;
		this.community = community;
		this.batches = batches;
		this.flags = flags;
	}

	@GetMapping("/audit-tasks")
	Map<String, Object> listAuditTasks(@RequestParam(required = false) String state) {
		return Map.of("items", admin.listAuditTasks(state));
	}

	@PostMapping("/audit-tasks/{taskId}/decisions")
	Map<String, Object> decideAuditTask(Principal principal, @PathVariable UUID taskId,
			@Valid @RequestBody AuditDecision input) {
		return Map.of("data", admin.decideAuditTask(taskId, RequestActor.require(principal), input.command()));
	}

	@PostMapping("/prices/{targetType}/{targetId}/confirmations")
	Map<String, Object> confirmPrice(Principal principal, @PathVariable String targetType, @PathVariable UUID targetId,
			@Valid @RequestBody PriceConfirmation input) {
		return Map.of("data",
				admin.confirmPrice(RequestActor.require(principal), targetType, targetId, input.command()));
	}

	@GetMapping("/dashboard")
	Map<String, Object> getOperationsDashboard() {
		return Map.of("data", admin.dashboard());
	}

	@GetMapping("/config/{key}")
	Map<String, Object> getConfig(@PathVariable String key) {
		return Map.of("data", admin.getConfig(key));
	}

	@PutMapping("/config/{key}")
	Map<String, Object> updateConfig(Principal principal, @PathVariable String key,
			@Valid @RequestBody ConfigUpdate input) {
		return Map.of("data", admin.updateConfig(key, RequestActor.require(principal), input.command()));
	}

	@GetMapping("/category-config")
	Map<String, Object> getCategoryConfig() {
		return Map.of("data", admin.getCategoryConfig());
	}

	@PutMapping("/category-config")
	Map<String, Object> updateCategoryConfig(Principal principal, @Valid @RequestBody CategoryConfigUpdate input) {
		return Map.of("data", admin.updateCategoryConfig(RequestActor.require(principal), input.command()));
	}

	@GetMapping("/recommendation-config")
	Map<String, Object> getRecommendationConfig() {
		return Map.of("data", admin.getRecommendationConfig());
	}

	@PutMapping("/recommendation-config")
	Map<String, Object> updateRecommendationConfig(Principal principal,
			@Valid @RequestBody RecommendationConfigUpdate input) {
		return Map.of("data", admin.updateRecommendationConfig(RequestActor.require(principal), input.command()));
	}

	@GetMapping("/feature-flags")
	Map<String, Object> listFeatureFlags() {
		return Map.of("items", admin.listFeatureFlags());
	}

	@GetMapping("/feature-flags/{key}")
	Map<String, Object> getFeatureFlag(@PathVariable String key) {
		return Map.of("data", admin.getFeatureFlag(key));
	}

	@PutMapping("/feature-flags/{key}")
	Map<String, Object> updateFeatureFlag(Principal principal, @PathVariable String key,
			@Valid @RequestBody FeatureFlagUpdate input) {
		return Map.of("data", admin.updateFeatureFlag(key, RequestActor.require(principal), input.command()));
	}

	@PostMapping("/ai-suggestions")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> createAiSuggestion(Principal principal, @Valid @RequestBody AiSuggestionRequest input) {
		flags.requireEnabled("ai.enabled", RequestActor.require(principal).toString());
		return Map.of("data", admin.createAiSuggestion(input.command()));
	}

	@GetMapping("/ai-suggestions")
	Map<String, Object> listAiSuggestions(@RequestParam(required = false) String state) {
		return Map.of("items", admin.listAiSuggestions(state));
	}

	@PostMapping("/ai-suggestions/{suggestionId}/decisions")
	Map<String, Object> decideAiSuggestion(Principal principal, @PathVariable UUID suggestionId,
			@Valid @RequestBody AiSuggestionDecision input) {
		return Map.of("data", admin.decideAiSuggestion(suggestionId, RequestActor.require(principal), input.command()));
	}

	@GetMapping("/operation-audits")
	Map<String, Object> listOperationAudits(@RequestParam(required = false) String action) {
		return Map.of("items", admin.listOperationAudits(action));
	}

	@GetMapping("/tags")
	Map<String, Object> listTags() {
		return Map.of("items", community.listTags());
	}

	@PostMapping("/tags")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createTag(Principal principal, @Valid @RequestBody TagCreate input) {
		return Map.of("data", community.createTag(RequestActor.require(principal), input.command()));
	}

	@PutMapping("/tags/{tagId}")
	Map<String, Object> updateTag(Principal principal, @PathVariable UUID tagId, @Valid @RequestBody TagUpdate input) {
		return Map.of("data", community.updateTag(tagId, RequestActor.require(principal), input.command()));
	}

	@PostMapping("/tags/{tagId}/merges")
	Map<String, Object> mergeTag(Principal principal, @PathVariable UUID tagId, @Valid @RequestBody TagMerge input) {
		return Map.of("data", community.mergeTag(tagId, RequestActor.require(principal), input.command()));
	}

	@GetMapping("/batches")
	Map<String, Object> listBatches() {
		return Map.of("items", batches.listBatches());
	}

	@GetMapping("/batches/{batchId}")
	Map<String, Object> getBatch(@PathVariable UUID batchId) {
		return Map.of("data", batches.getBatch(batchId));
	}

	@PostMapping("/tag-batches")
	Map<String, Object> executeTagStatusBatch(Principal principal, @Valid @RequestBody TagStatusBatch input) {
		return Map.of("data", batches.executeTagStatusBatch(RequestActor.require(principal), input.command()));
	}

	record AuditDecision(@NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
			@NotBlank @Pattern(regexp = "ILLEGAL|ADVERTISING|COPYRIGHT|SENSITIVE|FILE_RISK|LOW_QUALITY|INCOMPLETE|INVALID_PRICE|OTHER") String reasonCode,
			@Size(max = 2000) String note, @Min(0) long expectedTaskVersion, UUID suggestionId) {
		AdminApplicationService.AuditDecisionCommand command() {
			if ("OTHER".equals(reasonCode) && (note == null || note.isBlank())) {
				throw new DomainException("AUDIT_NOTE_REQUIRED", "OTHER 原因必须说明");
			}
			return new AdminApplicationService.AuditDecisionCommand(decision, reasonCode, note, expectedTaskVersion);
		}
	}

	record MoneyInput(@NotBlank @Pattern(regexp = "^\\d{1,10}(\\.\\d{1,2})?$") String amount,
			@NotBlank @Pattern(regexp = "CNY") String currency) {
		BigDecimal decimal() {
			return new BigDecimal(amount);
		}
	}

	record PriceConfirmation(@NotBlank @Pattern(regexp = "FREE|PAID|PREVIEW|ATTACHMENT_PAID") String mode,
			@NotNull @Valid MoneyInput amount, @NotNull Instant validFrom, @NotBlank @Size(max = 1000) String reason,
			@Min(0) long expectedVersion) {
		AdminApplicationService.PriceCommand command() {
			return new AdminApplicationService.PriceCommand(mode, amount == null ? null : amount.decimal(), validFrom,
					reason, expectedVersion);
		}
	}

	record ConfigUpdate(@NotNull Object value, @Min(0) long expectedVersion,
			@NotBlank @Size(max = 1000) String reason) {
		AdminApplicationService.ConfigCommand command() {
			return new AdminApplicationService.ConfigCommand(value, expectedVersion, reason);
		}
	}

	record FeatureFlagUpdate(boolean enabled, Object rule, @Min(0) long expectedVersion,
			@NotBlank @Size(max = 1000) String reason) {
		AdminApplicationService.FeatureFlagCommand command() {
			return new AdminApplicationService.FeatureFlagCommand(enabled, rule, expectedVersion, reason);
		}
	}

	record CategoryItemInput(@NotBlank @Size(max = 48) String key, @NotBlank @Size(max = 48) String label,
			boolean enabled, @Min(1) Integer coreRank) {
		AdminApplicationService.CategoryItem item() {
			return new AdminApplicationService.CategoryItem(key, label, enabled, coreRank);
		}
	}
	record CategoryConfigUpdate(@NotNull @Size(min = 1, max = 50) java.util.List<@Valid CategoryItemInput> categories,
			@Min(0) long expectedVersion, @NotBlank @Size(max = 1000) String reason) {
		AdminApplicationService.CategoryConfigCommand command() {
			return new AdminApplicationService.CategoryConfigCommand(
					categories.stream().map(CategoryItemInput::item).toList(), expectedVersion, reason);
		}
	}
	record RecommendationItemInput(@NotBlank @Pattern(regexp = "CONTENT|DEMAND") String targetType,
			@NotNull UUID targetId, @Min(1) int rank) {
		AdminApplicationService.RecommendationItem item() {
			return new AdminApplicationService.RecommendationItem(targetType, targetId, rank);
		}
	}
	record RecommendationConfigUpdate(@NotNull @Size(max = 50) java.util.List<@Valid RecommendationItemInput> items,
			@Min(0) long expectedVersion, @NotBlank @Size(max = 1000) String reason) {
		AdminApplicationService.RecommendationConfigCommand command() {
			return new AdminApplicationService.RecommendationConfigCommand(
					items.stream().map(RecommendationItemInput::item).toList(), expectedVersion, reason);
		}
	}

	record AiSuggestionRequest(
			@NotBlank @Pattern(regexp = "TAGS|SUMMARY|CATEGORY|DEMAND_STRUCTURE|AUDIT_REASON") String kind,
			@NotBlank @Pattern(regexp = "CONTENT|DEMAND|AUDIT_TASK") String targetType, @NotNull UUID targetId,
			@NotNull UUID targetVersionId) {
		AdminApplicationService.AiSuggestionCommand command() {
			return new AdminApplicationService.AiSuggestionCommand(kind, targetType, targetId, targetVersionId);
		}
	}

	record AiSuggestionDecision(@NotBlank @Pattern(regexp = "ACCEPT|EDIT|REJECT") String decision,
			Map<String, Object> editedOutput, @Min(0) long expectedVersion) {
		AdminApplicationService.AiDecisionCommand command() {
			return new AdminApplicationService.AiDecisionCommand(decision, editedOutput, expectedVersion);
		}
	}

	record TagCreate(@NotBlank @Size(max = 48) String name,
			@Size(max = 20) java.util.List<@NotBlank @Size(max = 48) String> aliases,
			@NotBlank @Size(max = 1000) String reason) {
		CommunityApplicationService.TagCreateCommand command() {
			return new CommunityApplicationService.TagCreateCommand(name,
					aliases == null ? java.util.List.of() : aliases, reason);
		}
	}

	record TagUpdate(boolean enabled, @Size(max = 20) java.util.List<@NotBlank @Size(max = 48) String> aliases,
			@Min(0) long expectedVersion, @NotBlank @Size(max = 1000) String reason) {
		CommunityApplicationService.TagUpdateCommand command() {
			return new CommunityApplicationService.TagUpdateCommand(enabled,
					aliases == null ? java.util.List.of() : aliases, expectedVersion, reason);
		}
	}

	record TagMerge(@NotNull UUID targetTagId, @Min(0) long expectedSourceVersion, @Min(0) long expectedTargetVersion,
			@NotBlank @Size(max = 1000) String reason) {
		CommunityApplicationService.TagMergeCommand command() {
			return new CommunityApplicationService.TagMergeCommand(targetTagId, expectedSourceVersion,
					expectedTargetVersion, reason);
		}
	}

	record TagStatusItemInput(@NotNull UUID tagId, @Min(0) long expectedVersion) {
		BatchApplicationService.TagStatusItem item() {
			return new BatchApplicationService.TagStatusItem(tagId, expectedVersion);
		}
	}
	record TagStatusBatch(boolean enabled,
			@NotNull @Size(min = 1, max = 50) java.util.List<@Valid TagStatusItemInput> items,
			@NotBlank @Size(max = 1000) String reason) {
		BatchApplicationService.TagStatusBatchCommand command() {
			return new BatchApplicationService.TagStatusBatchCommand(enabled,
					items.stream().map(TagStatusItemInput::item).toList(), reason);
		}
	}
}
