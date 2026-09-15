package com.technexus.server.web;

import com.technexus.common.domain.DomainException;
import java.security.Principal;
import java.util.UUID;

final class RequestActor {
	private RequestActor() {
	}

	static UUID require(Principal principal) {
		if (principal == null)
			throw new DomainException("AUTH_REQUIRED", "需要登录");
		return UUID.fromString(principal.getName());
	}

	static UUID optional(Principal principal) {
		return principal == null ? null : UUID.fromString(principal.getName());
	}
}
