package com.technexus.content.api;

import com.technexus.content.domain.Post;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostRepository {
	Optional<Post> findById(UUID publicId);
	List<Post> listPublished(int limit);
	void add(Post post);
	void save(Post post);
}
