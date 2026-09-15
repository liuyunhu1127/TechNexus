package com.technexus.server.web;

import com.technexus.server.user.application.ProfileApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {
	private final ProfileApplicationService profiles;
	public ProfileController(ProfileApplicationService profiles) {
		this.profiles = profiles;
	}

	@GetMapping
	Map<String, Object> getMyProfile(Principal principal) {
		return Map.of("data", profiles.get(RequestActor.require(principal)));
	}

	@PatchMapping
	Map<String, Object> updateMyProfile(Principal principal, @Valid @RequestBody ProfilePatch input) {
		return Map.of("data", profiles.update(RequestActor.require(principal), input.expectedVersion(),
				input.displayName(), input.bio()));
	}

	record ProfilePatch(@Size(min = 1, max = 80) String displayName, @Size(max = 1_000) String bio,
			@NotNull @Min(0) Long expectedVersion) {
	}
}
