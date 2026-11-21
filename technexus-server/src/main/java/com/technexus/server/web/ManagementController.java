package com.technexus.server.web;

import com.technexus.content.domain.ContentState;
import com.technexus.server.admin.application.ManagementApplicationService;
import com.technexus.user.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class ManagementController {
	private final ManagementApplicationService management;

	public ManagementController(ManagementApplicationService management) {
		this.management = management;
	}

	@GetMapping("/users")
	ManagementApplicationService.Page<ManagementApplicationService.ManagedUser> users(
			@RequestParam(defaultValue = "") @Size(max = 254) String q,
			@RequestParam(required = false) UserStatus status,
			@RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page) {
		return management.listUsers(q, status, page);
	}

	@PutMapping("/users/{id}/status")
	Map<String, Object> userStatus(Principal principal, @PathVariable UUID id, @Valid @RequestBody StatusChange input) {
		return Map.of("data", management.changeUserStatus(RequestActor.require(principal), id, input.enabled(),
				input.expectedVersion(), input.reason()));
	}

	@GetMapping("/posts")
	ManagementApplicationService.Page<com.technexus.content.api.PostRepository.ManagementEntry> posts(
			@RequestParam(defaultValue = "") @Size(max = 160) String q,
			@RequestParam(required = false) ContentState state,
			@RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page) {
		return management.listPosts(q, state, page);
	}

	@GetMapping("/posts/{id}")
	Map<String, Object> post(@PathVariable UUID id) {
		return Map.of("data", management.getPost(id));
	}

	@PutMapping("/posts/{id}/publication")
	Map<String, Object> postState(Principal principal, @PathVariable UUID id, @Valid @RequestBody StatusChange input) {
		return Map.of("data", management.changePostState(RequestActor.require(principal), id, input.enabled(),
				input.expectedVersion(), input.reason()));
	}

	record StatusChange(@NotNull Boolean enabled, @NotNull @Min(0) Long expectedVersion,
			@NotBlank @Size(max = 1000) String reason) {
	}
}
