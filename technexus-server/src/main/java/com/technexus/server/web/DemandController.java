package com.technexus.server.web;

import com.technexus.server.demand.application.DemandApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/demands")
public class DemandController {
	private final DemandApplicationService demands;

	public DemandController(DemandApplicationService demands) {
		this.demands = demands;
	}

	@GetMapping
	Map<String, Object> listDemands() {
		return Map.of("items", demands.listPublic());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createDemand(Principal principal, @Valid @RequestBody DemandWrite input) {
		return Map.of("data", demands.create(RequestActor.require(principal), input.command()));
	}

	@GetMapping("/{demandId}")
	Map<String, Object> getDemand(Principal principal, @PathVariable UUID demandId) {
		return Map.of("data", demands.get(demandId, RequestActor.optional(principal)));
	}

	@PatchMapping("/{demandId}")
	Map<String, Object> updateDemand(Principal principal, @PathVariable UUID demandId,
			@Valid @RequestBody DemandPatch input) {
		return Map.of("data",
				demands.update(demandId, RequestActor.require(principal), input.expectedVersion(), input.command()));
	}

	@PostMapping("/{demandId}/submit")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> submitDemandForReview(Principal principal, @PathVariable UUID demandId,
			@Valid @RequestBody ExpectedVersion input) {
		return Map.of("data", demands.submit(demandId, RequestActor.require(principal), input.expectedVersion()));
	}

	@PostMapping("/{demandId}/proposals")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createProposal(Principal principal, @PathVariable UUID demandId,
			@Valid @RequestBody ProposalWrite input) {
		var actor = RequestActor.require(principal);
		return Map.of("data", demands.createProposal(demandId, actor, input.command()));
	}

	@PostMapping("/{demandId}/transitions")
	Map<String, Object> transitionDemand(Principal principal, @PathVariable UUID demandId,
			@Valid @RequestBody DemandTransition input) {
		return Map.of("data", demands.transition(demandId, RequestActor.require(principal), input.expectedVersion(),
				input.action(), input.proposalId()));
	}

	record MoneyInput(@NotBlank @Pattern(regexp = "\\d{1,10}(\\.\\d{1,2})?") String amount,
			@NotBlank @Pattern(regexp = "CNY") String currency) {
		BigDecimal decimal() {
			return new BigDecimal(amount);
		}
	}

	record DemandWrite(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 50_000) String description,
			@Valid MoneyInput budgetMin, @Valid MoneyInput budgetMax, Instant deadlineAt,
			@NotBlank @Pattern(regexp = "PUBLIC|LOGIN_REQUIRED|PARTICIPANTS|OWNER_ONLY|ADMIN_ONLY") String visibility) {
		DemandApplicationService.DemandCommand command() {
			return new DemandApplicationService.DemandCommand(title, description,
					budgetMin == null ? null : budgetMin.decimal(), budgetMax == null ? null : budgetMax.decimal(),
					deadlineAt, visibility);
		}
	}

	record DemandPatch(@Size(min = 1, max = 160) String title, @Size(min = 1, max = 50_000) String description,
			@Valid MoneyInput budgetMin, @Valid MoneyInput budgetMax, Instant deadlineAt,
			@Pattern(regexp = "PUBLIC|LOGIN_REQUIRED|PARTICIPANTS|OWNER_ONLY|ADMIN_ONLY") String visibility,
			@NotNull @Min(0) Long expectedVersion) {
		DemandApplicationService.DemandCommand command() {
			return new DemandApplicationService.DemandCommand(title, description,
					budgetMin == null ? null : budgetMin.decimal(), budgetMax == null ? null : budgetMax.decimal(),
					deadlineAt, visibility);
		}
	}

	record ExpectedVersion(@NotNull @Min(0) Long expectedVersion) {
	}

	record ProposalWrite(@NotBlank @Size(max = 50_000) String plan,
			@NotNull @Size(min = 1, max = 30) List<@NotBlank @Size(max = 80) String> techStack,
			@NotNull @Min(1) @Max(3650) Integer estimatedDays, @NotNull @Valid MoneyInput suggestedQuote) {
		DemandApplicationService.ProposalCommand command() {
			return new DemandApplicationService.ProposalCommand(plan, techStack, estimatedDays,
					suggestedQuote.decimal());
		}
	}

	record DemandTransition(
			@NotBlank @Pattern(regexp = "START_NEGOTIATION|CONFIRM_PROPOSAL|START_WORK|DELIVER|COMPLETE|CANCEL|OFFLINE") String action,
			UUID proposalId, @Size(max = 1000) String reason, @NotNull @Min(0) Long expectedVersion) {
	}
}
