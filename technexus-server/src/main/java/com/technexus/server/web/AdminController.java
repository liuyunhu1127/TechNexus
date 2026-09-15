package com.technexus.server.web;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.AdminApplicationService;
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

	public AdminController(AdminApplicationService admin) {
		this.admin = admin;
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

	@PostMapping("/ai-suggestions")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> createAiSuggestion(@Valid @RequestBody AiSuggestionRequest input) {
		return Map.of("data", admin.createAiSuggestion(input.command()));
	}

	@PostMapping("/ai-suggestions/{suggestionId}/decisions")
	Map<String, Object> decideAiSuggestion(Principal principal, @PathVariable UUID suggestionId,
			@Valid @RequestBody AiSuggestionDecision input) {
		return Map.of("data", admin.decideAiSuggestion(suggestionId, RequestActor.require(principal), input.command()));
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
}
