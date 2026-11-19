package com.technexus.server.web;

import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.admin.application.FeatureFlagEvaluator;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

@RestController
@RequestMapping("/api/v1/contents")
public class ContentController {
	private final ContentApplicationService contents;
	private final DemandApplicationService demands;
	private final CommunityApplicationService community;
	private final FeatureFlagEvaluator flags;

	public ContentController(ContentApplicationService contents, DemandApplicationService demands,
			CommunityApplicationService community, FeatureFlagEvaluator flags) {
		this.contents = contents;
		this.demands = demands;
		this.community = community;
		this.flags = flags;
	}

	@GetMapping
	Map<String, Object> listContents() {
		return Map.of("items", contents.listPublic());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	Map<String, Object> createContent(Principal principal, @Valid @RequestBody ContentWrite input) {
		var actor = RequestActor.require(principal);
		var created = contents.create(actor, input.command());
		if (input.sourceDemandId() != null) {
			if (!"SOLUTION".equals(input.type()))
				throw new com.technexus.common.domain.DomainException("LINEAGE_TYPE_INVALID", "只有解决方案可以关联来源需求");
			demands.linkSolution(input.sourceDemandId(), created.id());
		}
		if (input.sourceSolutionId() != null) {
			if (!"DOCUMENT".equals(input.type()))
				throw new com.technexus.common.domain.DomainException("LINEAGE_TYPE_INVALID", "只有技术文档可以关联来源解决方案");
			contents.get(input.sourceSolutionId(), actor);
			demands.linkKnowledge(input.sourceSolutionId(), created.id());
		}
		return Map.of("data", created);
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
		flags.requireEnabled("comments.enabled", actor.toString());
		contents.get(contentId, actor);
		return Map.of("data", community.createComment(actor, contentId, input.parentId(), input.body()));
	}

	@GetMapping("/{contentId}/comments")
	Map<String, Object> listComments(Principal principal, @PathVariable UUID contentId) {
		contents.get(contentId, RequestActor.optional(principal));
		return Map.of("items", community.listComments(contentId));
	}

	@DeleteMapping("/{contentId}/comments/{commentId}")
	Map<String, Object> deleteComment(Principal principal, @PathVariable UUID contentId, @PathVariable UUID commentId,
			@Valid @RequestBody ExpectedVersion input) {
		contents.get(contentId, RequestActor.require(principal));
		return Map.of("data", community.deleteComment(RequestActor.require(principal), contentId, commentId,
				input.expectedVersion()));
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
			@Size(max = 20) List<UUID> fileIds, UUID sourceDemandId, UUID sourceSolutionId) {
		ContentApplicationService.ContentCommand command() {
			return new ContentApplicationService.ContentCommand(type, visibility, title, summary, body, tagIds,
					fileIds);
		}
	}

	record ContentPatch(@Pattern(regexp = "PUBLIC|LOGIN_REQUIRED|PARTICIPANTS|OWNER_ONLY|ADMIN_ONLY") String visibility,
			@Size(min = 1, max = 160) String title, @Size(max = 500) String summary,
			@Size(min = 1, max = 200_000) String body, @Size(max = 10) List<UUID> tagIds,
			@Size(max = 20) List<UUID> fileIds, @NotNull @Min(0) Long expectedVersion) {
		ContentApplicationService.ContentCommand command() {
			return new ContentApplicationService.ContentCommand(null, visibility, title, summary, body, tagIds,
					fileIds);
		}
	}

	record ExpectedVersion(@NotNull @Min(0) Long expectedVersion) {
	}
	record CommentWrite(@NotBlank @Size(max = 5_000) String body, UUID parentId) {
	}
}
