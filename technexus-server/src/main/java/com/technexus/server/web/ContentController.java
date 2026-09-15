package com.technexus.server.web;

import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.community.application.CommunityApplicationService;
import com.technexus.server.demand.application.DemandApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
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
@RequestMapping("/api/v1/contents")
public class ContentController {
	private final ContentApplicationService contents;
	private final DemandApplicationService demands;
	private final CommunityApplicationService community;

	public ContentController(ContentApplicationService contents, DemandApplicationService demands,
			CommunityApplicationService community) {
		this.contents = contents;
		this.demands = demands;
		this.community = community;
	}

	@GetMapping
	Map<String, Object> listContents() {
		return Map.of("items", contents.listPublic());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createContent(Principal principal, @Valid @RequestBody ContentWrite input) {
		return Map.of("data", contents.create(RequestActor.require(principal), input.command()));
	}

	@GetMapping("/{contentId}")
	Map<String, Object> getContent(Principal principal, @PathVariable UUID contentId) {
		return Map.of("data", contents.get(contentId, RequestActor.optional(principal)));
	}

	@PatchMapping("/{contentId}")
	Map<String, Object> updateContent(Principal principal, @PathVariable UUID contentId,
			@Valid @RequestBody ContentPatch input) {
		return Map.of("data",
				contents.update(contentId, RequestActor.require(principal), input.expectedVersion(), input.command()));
	}

	@PostMapping("/{contentId}/submit")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> submitContentForReview(Principal principal, @PathVariable UUID contentId,
			@Valid @RequestBody ExpectedVersion input) {
		return Map.of("data", contents.submit(contentId, RequestActor.require(principal), input.expectedVersion()));
	}

	@PostMapping("/{contentId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createComment(Principal principal, @PathVariable UUID contentId,
			@Valid @RequestBody CommentWrite input) {
		var actor = RequestActor.require(principal);
		contents.get(contentId, actor);
		return Map.of("data", community.createComment(actor, contentId, input.parentId(), input.body()));
	}

	@PostMapping("/{contentId}/demands")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createDemandFromContent(Principal principal, @PathVariable UUID contentId,
			@Valid @RequestBody DemandController.DemandWrite input) {
		var actor = RequestActor.require(principal);
		contents.get(contentId, actor);
		return Map.of("data", demands.createFromContent(contentId, actor, input.command()));
	}

	record ContentWrite(@NotBlank @Pattern(regexp = "POST|DOCUMENT|PROBLEM|SOLUTION") String type,
			@NotBlank @Pattern(regexp = "PUBLIC|LOGIN_REQUIRED|PARTICIPANTS|OWNER_ONLY|ADMIN_ONLY") String visibility,
			@NotBlank @Size(max = 160) String title, @Size(max = 500) String summary,
			@NotBlank @Size(max = 200_000) String body, @Size(max = 10) List<UUID> tagIds,
			@Size(max = 20) List<UUID> fileIds) {
		ContentApplicationService.ContentCommand command() {
			return new ContentApplicationService.ContentCommand(type, visibility, title, summary, body);
		}
	}

	record ContentPatch(@Pattern(regexp = "PUBLIC|LOGIN_REQUIRED|PARTICIPANTS|OWNER_ONLY|ADMIN_ONLY") String visibility,
			@Size(min = 1, max = 160) String title, @Size(max = 500) String summary,
			@Size(min = 1, max = 200_000) String body, @Size(max = 10) List<UUID> tagIds,
			@Size(max = 20) List<UUID> fileIds, @NotNull @Min(0) Long expectedVersion) {
		ContentApplicationService.ContentCommand command() {
			return new ContentApplicationService.ContentCommand(null, visibility, title, summary, body);
		}
	}

	record ExpectedVersion(@NotNull @Min(0) Long expectedVersion) {
	}
	record CommentWrite(@NotBlank @Size(max = 5_000) String body, UUID parentId) {
	}
}
