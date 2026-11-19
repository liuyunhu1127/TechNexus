package com.technexus.server.community.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.community.api.CommentRepository;
import com.technexus.community.domain.Comment;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcCommentRepository implements CommentRepository {
	private final JdbcTemplate jdbc;
	public JdbcCommentRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void add(Comment comment) {
		if (comment.parentId() != null) {
			var parents = jdbc.queryForObject(
					"SELECT COUNT(*) FROM tn_comment WHERE public_id=? AND target_type=? AND target_public_id=?",
					Integer.class, bytes(comment.parentId()), comment.target().type(),
					bytes(comment.target().publicId()));
			if (parents == null || parents != 1)
				throw new DomainException("COMMENT_PARENT_INVALID", "父评论不存在");
		}
		var changed = jdbc.update(
				"""
						INSERT INTO tn_comment(public_id,author_id,parent_id,root_id,target_type,target_public_id,body,display_state,version,created_at,updated_at)
						SELECT ?,u.id,
						  (SELECT id FROM tn_comment WHERE public_id=?),
						  (SELECT COALESCE(root_id,id) FROM tn_comment WHERE public_id=?),
						  ?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
						FROM tn_user u WHERE u.public_id=?
						""",
				bytes(comment.publicId()), nullableBytes(comment.parentId()), nullableBytes(comment.parentId()),
				comment.target().type(), bytes(comment.target().publicId()), comment.body(), comment.state().name(),
				comment.version(), bytes(comment.authorId()));
		if (changed != 1)
			throw new DomainException("AUTH_REQUIRED", "评论作者不存在");
	}
	@Override
	public List<CommentItem> listFor(String targetType, UUID targetId, int limit) {
		return jdbc.query("""
				SELECT c.public_id,u.public_id author_public_id,p.public_id parent_public_id,c.body,c.display_state,
				       c.version,c.created_at
				FROM tn_comment c JOIN tn_user u ON u.id=c.author_id
				LEFT JOIN tn_comment p ON p.id=c.parent_id
				WHERE c.target_type=? AND c.target_public_id=?
				ORDER BY COALESCE(c.root_id,c.id),c.created_at,c.id LIMIT ?
				""",
				(result, row) -> new CommentItem(uuid(result.getBytes("public_id")),
						uuid(result.getBytes("author_public_id")), nullableUuid(result.getBytes("parent_public_id")),
						result.getString("body"), result.getString("display_state"), result.getLong("version"),
						result.getTimestamp("created_at").toInstant()),
				targetType, bytes(targetId), Math.min(limit, 200));
	}
	@Override
	public Optional<CommentItem> findById(UUID commentId) {
		return jdbc.query("""
				SELECT c.public_id,u.public_id author_public_id,p.public_id parent_public_id,c.body,c.display_state,
				       c.version,c.created_at
				FROM tn_comment c JOIN tn_user u ON u.id=c.author_id LEFT JOIN tn_comment p ON p.id=c.parent_id
				WHERE c.public_id=?
				""",
				(result, row) -> new CommentItem(uuid(result.getBytes("public_id")),
						uuid(result.getBytes("author_public_id")), nullableUuid(result.getBytes("parent_public_id")),
						result.getString("body"), result.getString("display_state"), result.getLong("version"),
						result.getTimestamp("created_at").toInstant()),
				bytes(commentId)).stream().findFirst();
	}
	@Override
	public Optional<UUID> findInteractionRecipient(UUID contentId, UUID parentId) {
		if (parentId != null)
			return jdbc.query(
					"SELECT u.public_id FROM tn_comment c JOIN tn_user u ON u.id=c.author_id WHERE c.public_id=?",
					(result, row) -> uuid(result.getBytes(1)), bytes(parentId)).stream().findFirst();
		return jdbc.query("""
				SELECT u.public_id FROM tn_article a JOIN tn_user u ON u.id=a.owner_id WHERE a.public_id=?
				UNION ALL
				SELECT u.public_id FROM tn_post p JOIN tn_user u ON u.id=p.owner_id WHERE p.public_id=? LIMIT 1
				""", (result, row) -> uuid(result.getBytes(1)), bytes(contentId), bytes(contentId)).stream()
				.findFirst();
	}
	@Override
	public boolean softDelete(UUID commentId, UUID contentId, UUID authorId, long expectedVersion, Instant deletedAt) {
		return jdbc.update("""
				UPDATE tn_comment c JOIN tn_user u ON u.id=c.author_id
				SET c.body='',c.display_state='DELETED',c.version=c.version+1,c.updated_at=?
				WHERE c.public_id=? AND c.target_type='CONTENT' AND c.target_public_id=? AND u.public_id=?
				  AND c.version=? AND c.display_state='PUBLISHED'
				""", Timestamp.from(deletedAt), bytes(commentId), bytes(contentId), bytes(authorId),
				expectedVersion) == 1;
	}
	private static byte[] nullableBytes(UUID value) {
		return value == null ? null : bytes(value);
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID nullableUuid(byte[] value) {
		return value == null ? null : uuid(value);
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
