package com.technexus.user.api;

import com.technexus.user.domain.User;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
	Optional<User> findById(UUID publicId);
	Optional<User> findByEmail(String normalizedEmail);
	void save(User user);
}
