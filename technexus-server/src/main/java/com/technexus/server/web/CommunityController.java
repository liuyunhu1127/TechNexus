package com.technexus.server.web;

import com.technexus.server.community.application.CommunityApplicationService;
import com.technexus.server.content.application.ContentApplicationService;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class CommunityController {
	private final ContentApplicationService contents;
	private final CommunityApplicationService community;
	public CommunityController(ContentApplicationService contents, CommunityApplicationService community) {
		this.contents = contents;
		this.community = community;
	}

	@GetMapping("/search")
	Map<String, Object> search(@RequestParam(defaultValue = "") @Size(max = 200) String q) {
		return Map.of("items", contents.searchPublic(q));
	}

	@GetMapping("/notifications")
	Map<String, Object> listNotifications(Principal principal) {
		return Map.of("items", community.listNotifications(RequestActor.require(principal)));
	}

	@PostMapping("/notifications/{notificationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void readNotification(Principal principal, @PathVariable UUID notificationId) {
		community.readNotification(RequestActor.require(principal), notificationId);
	}
}
