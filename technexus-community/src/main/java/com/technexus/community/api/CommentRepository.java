package com.technexus.community.api;

import com.technexus.community.domain.Comment;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository {
	void add(Comment comment);
	List<CommentItem> listFor(String targetType, UUID targetId, int limit);
	Optional<CommentItem> findById(UUID commentId);
	Optional<UUID> findInteractionRecipient(UUID contentId, UUID parentId);
	boolean softDelete(UUID commentId, UUID contentId, UUID authorId, long expectedVersion, Instant deletedAt);

	record CommentItem(UUID id, UUID authorId, UUID parentId, String body, String state, long version,
			Instant createdAt) {
	}
}
