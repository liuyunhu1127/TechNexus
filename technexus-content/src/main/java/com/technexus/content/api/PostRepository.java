package com.technexus.content.api;

import com.technexus.content.domain.Post;
import com.technexus.content.domain.ContentState;
import com.technexus.content.domain.PostType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostRepository {
	Optional<Post> findById(UUID publicId);
	List<Post> listPublished(int limit);
	List<ManagementEntry> listForManagement(String query, ContentState state, int offset, int limit);
	void add(Post post);
	void save(Post post);

	record ManagementEntry(UUID id, UUID ownerId, String title, PostType type, ContentState state, long version) {
	}
}
