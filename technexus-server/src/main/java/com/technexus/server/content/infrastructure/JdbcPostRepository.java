package com.technexus.server.content.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import com.technexus.content.api.PostRepository;
import com.technexus.content.domain.ContentState;
import com.technexus.content.domain.Post;
import com.technexus.content.domain.PostType;
import com.technexus.content.domain.PostVersion;
import com.technexus.content.domain.VersionState;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcPostRepository implements PostRepository {
	private final JdbcTemplate jdbc;
	public JdbcPostRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Post> findById(UUID publicId) {
		return jdbc
				.query("""
						SELECT p.public_id,u.public_id owner_public_id,p.post_type,p.state,p.visibility,p.version,pv.public_id published_public_id
						FROM tn_post p JOIN tn_user u ON u.id=p.owner_id
						LEFT JOIN tn_post_version pv ON pv.id=p.published_version_id WHERE p.public_id=?
						""",
						(result, row) -> parent(result), bytes(publicId))
				.stream().map(this::restore).findFirst();
	}

	@Override
	public List<Post> listPublished(int limit) {
		return jdbc.query(
				"SELECT public_id FROM tn_post WHERE state='PUBLISHED' AND visibility='PUBLIC' ORDER BY published_at DESC LIMIT ?",
				(result, row) -> uuid(result.getBytes(1)), Math.min(Math.max(limit, 1), 100)).stream()
				.map(id -> findById(id).orElseThrow()).toList();
	}

	@Override
	public void add(Post post) {
		var changed = jdbc.update("""
				INSERT INTO tn_post(public_id,owner_id,post_type,state,visibility,version,created_at,updated_at)
				SELECT ?,id,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
				""", bytes(post.publicId()), post.type().name(), post.state().name(), post.visibility().name(),
				post.version(), bytes(post.ownerId()));
		if (changed != 1)
			throw new DomainException("AUTH_REQUIRED", "内容所有者不存在");
		post.versions().forEach(version -> insertVersion(post.publicId(), version));
	}

	@Override
	public void save(Post post) {
		post.versions().forEach(version -> upsertVersion(post.publicId(), version));
		var changed = jdbc.update(
				"""
						UPDATE tn_post SET state=?,visibility=?,version=?,
						  published_version_id=(SELECT id FROM tn_post_version WHERE public_id=?),
						  published_at=CASE WHEN ?='PUBLISHED' THEN COALESCE(published_at,CURRENT_TIMESTAMP(6)) ELSE published_at END,
						  updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND version=?
						""",
				post.state().name(), post.visibility().name(), post.version(), nullableBytes(post.publishedVersionId()),
				post.state().name(), bytes(post.publicId()), post.version() - 1);
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "内容版本已变化");
	}

	private Post restore(Parent parent) {
		var versions = jdbc.query(
				"SELECT * FROM tn_post_version WHERE post_id=(SELECT id FROM tn_post WHERE public_id=?) ORDER BY revision",
				this::version, bytes(parent.publicId));
		var pending = versions.stream().filter(value -> value.state() == VersionState.PENDING_REVIEW)
				.map(PostVersion::publicId).findFirst().orElse(null);
		return Post.restore(parent.publicId, parent.ownerId, parent.type, parent.visibility, versions, parent.state,
				pending, parent.publishedVersionId, parent.version);
	}
	private PostVersion version(ResultSet result, int row) throws SQLException {
		return PostVersion.restore(uuid(result.getBytes("public_id")), result.getInt("revision"),
				result.getString("title"), result.getString("body"), VersionState.valueOf(result.getString("state")));
	}
	private void upsertVersion(UUID postId, PostVersion version) {
		var changed = jdbc.update(
				"UPDATE tn_post_version SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=?",
				version.state().name(), bytes(version.publicId()));
		if (changed == 0)
			insertVersion(postId, version);
	}
	private void insertVersion(UUID postId, PostVersion version) {
		jdbc.update(
				"""
						INSERT INTO tn_post_version(public_id,post_id,revision,title,summary,body,state,checksum,created_at,updated_at)
						SELECT ?,id,?,?,NULL,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_post WHERE public_id=?
						""",
				bytes(version.publicId()), version.revision(), version.title(), version.body(), version.state().name(),
				checksum(version.title(), version.body()), bytes(postId));
	}
	private static Parent parent(ResultSet result) throws SQLException {
		return new Parent(uuid(result.getBytes("public_id")), uuid(result.getBytes("owner_public_id")),
				PostType.valueOf(result.getString("post_type")), ContentState.valueOf(result.getString("state")),
				Visibility.valueOf(result.getString("visibility")), result.getLong("version"),
				nullableUuid(result.getBytes("published_public_id")));
	}
	private static byte[] checksum(String... values) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			for (var value : values)
				digest.update(value.getBytes(StandardCharsets.UTF_8));
			return digest.digest();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
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
	private record Parent(UUID publicId, UUID ownerId, PostType type, ContentState state, Visibility visibility,
			long version, UUID publishedVersionId) {
	}
}
