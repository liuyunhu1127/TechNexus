package com.technexus.server.content.infrastructure;

import com.technexus.content.api.PostRepository;
import com.technexus.content.domain.ContentState;
import com.technexus.content.domain.Post;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryPostRepository implements PostRepository {
	private final Map<UUID, Post> values = new ConcurrentHashMap<>();
	@Override
	public Optional<Post> findById(UUID publicId) {
		return Optional.ofNullable(values.get(publicId));
	}
	@Override
	public List<Post> listPublished(int limit) {
		return values.values().stream().filter(value -> value.state() == ContentState.PUBLISHED).limit(limit).toList();
	}
	@Override
	public void add(Post post) {
		values.put(post.publicId(), post);
	}
	@Override
	public void save(Post post) {
		values.put(post.publicId(), post);
	}
}
