package com.technexus.server.user.application;

import com.technexus.common.domain.DomainException;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProfileApplicationService {
	private final UserRepository users;
	public ProfileApplicationService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public ProfileView get(UUID userId) {
		return view(require(userId));
	}

	public ProfileView update(UUID userId, long expectedVersion, String displayName, String bio) {
		var user = require(userId);
		if (user.version() != expectedVersion)
			throw new DomainException("VERSION_CONFLICT", "用户资料版本已变化");
		user.updateProfile(displayName == null ? user.displayName() : displayName, bio == null ? user.bio() : bio);
		users.save(user);
		return view(user);
	}

	private User require(UUID id) {
		return users.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "用户不存在"));
	}
	private static ProfileView view(User user) {
		return new ProfileView(user.publicId(), user.status().name(), user.displayName(), user.bio(), user.version());
	}
	public record ProfileView(UUID id, String status, String displayName, String bio, long version) {
	}
}
