package com.technexus.server.user.infrastructure;

import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryUserRepository implements UserRepository {
	private final Map<UUID, User> values = new ConcurrentHashMap<>();
	@Override
	public Optional<User> findById(UUID publicId) {
		return Optional.ofNullable(values.get(publicId));
	}
	@Override
	public Optional<User> findByEmail(String email) {
		return values.values().stream().filter(value -> value.normalizedEmail().equals(email)).findFirst();
	}

	@Override
	public List<User> listForManagement(String query, UserStatus status, int offset, int limit) {
		return values.values().stream().filter(user -> status == null || user.status() == status)
				.filter(user -> user.normalizedEmail().contains(query)
						|| user.displayName().toLowerCase(Locale.ROOT).contains(query))
				.sorted(Comparator.comparing(User::publicId).reversed()).skip(offset).limit(limit).toList();
	}
	@Override
	public void save(User user) {
		values.put(user.publicId(), user);
	}
}
