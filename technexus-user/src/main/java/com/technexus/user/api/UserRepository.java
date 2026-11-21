package com.technexus.user.api;

import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
	Optional<User> findById(UUID publicId);
	Optional<User> findByEmail(String normalizedEmail);
	List<User> listForManagement(String query, UserStatus status, int offset, int limit);
	void save(User user);
}
