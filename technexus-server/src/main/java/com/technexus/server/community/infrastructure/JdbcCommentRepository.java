package com.technexus.server.community.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.community.api.CommentRepository;
import com.technexus.community.domain.Comment;
import java.nio.ByteBuffer;
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
	private static byte[] nullableBytes(UUID value) {
		return value == null ? null : bytes(value);
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
}
