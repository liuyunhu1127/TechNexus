package com.technexus.server.community.infrastructure;

import com.technexus.community.api.CommentRepository;
import com.technexus.community.domain.Comment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryCommentRepository implements CommentRepository {
	private final List<Comment> values = new CopyOnWriteArrayList<>();
	@Override
	public void add(Comment comment) {
		values.add(comment);
	}
	@Override
	public List<CommentItem> listFor(String targetType, UUID targetId, int limit) {
		return values.stream()
				.filter(value -> value.target().type().equals(targetType) && value.target().publicId().equals(targetId))
				.limit(limit).map(value -> item(value, Instant.EPOCH)).toList();
	}
	@Override
	public Optional<CommentItem> findById(UUID commentId) {
		return values.stream().filter(value -> value.publicId().equals(commentId)).findFirst()
				.map(value -> item(value, Instant.EPOCH));
	}
	@Override
	public Optional<UUID> findInteractionRecipient(UUID contentId, UUID parentId) {
		if (parentId == null)
			return Optional.empty();
		return values.stream().filter(value -> value.publicId().equals(parentId)).map(Comment::authorId).findFirst();
	}
	@Override
	public boolean softDelete(UUID commentId, UUID contentId, UUID authorId, long expectedVersion, Instant deletedAt) {
		var value = values.stream().filter(item -> item.publicId().equals(commentId)).findFirst().orElse(null);
		if (value == null || !value.target().publicId().equals(contentId) || !value.authorId().equals(authorId)
				|| value.version() != expectedVersion)
			return false;
		value.delete(authorId);
		return true;
	}
	private static CommentItem item(Comment value, Instant createdAt) {
		return new CommentItem(value.publicId(), value.authorId(), value.parentId(), value.body(), value.state().name(),
				value.version(), createdAt);
	}
}
