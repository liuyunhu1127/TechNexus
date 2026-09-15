package com.technexus.server.content.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import com.technexus.content.api.ArticleRepository;
import com.technexus.content.domain.Article;
import com.technexus.content.domain.ArticleVersion;
import com.technexus.content.domain.ContentState;
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
public class JdbcArticleRepository implements ArticleRepository {
	private final JdbcTemplate jdbc;
	public JdbcArticleRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Article> findById(UUID publicId) {
		return jdbc
				.query("""
						SELECT a.public_id,u.public_id owner_public_id,a.state,a.visibility,a.version,pv.public_id published_public_id
						FROM tn_article a JOIN tn_user u ON u.id=a.owner_id
						LEFT JOIN tn_article_version pv ON pv.id=a.published_version_id WHERE a.public_id=?
						""",
						(result, row) -> parent(result), bytes(publicId))
				.stream().map(this::restore).findFirst();
	}

	@Override
	public List<Article> listPublished(int limit) {
		return jdbc.query(
				"SELECT public_id FROM tn_article WHERE state='PUBLISHED' AND visibility='PUBLIC' ORDER BY published_at DESC LIMIT ?",
				(result, row) -> uuid(result.getBytes(1)), Math.min(Math.max(limit, 1), 100)).stream()
				.map(id -> findById(id).orElseThrow()).toList();
	}

	@Override
	public void add(Article article) {
		var changed = jdbc.update("""
				INSERT INTO tn_article(public_id,owner_id,state,visibility,version,created_at,updated_at)
				SELECT ?,id,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
				""", bytes(article.publicId()), article.state().name(), article.visibility().name(), article.version(),
				bytes(article.ownerId()));
		if (changed != 1)
			throw new DomainException("AUTH_REQUIRED", "内容所有者不存在");
		article.versions().forEach(version -> insertVersion(article.publicId(), version));
	}

	@Override
	public void save(Article article) {
		article.versions().forEach(version -> upsertVersion(article.publicId(), version));
		var changed = jdbc.update(
				"""
						UPDATE tn_article SET state=?,visibility=?,version=?,
						  published_version_id=(SELECT id FROM tn_article_version WHERE public_id=?),
						  published_at=CASE WHEN ?='PUBLISHED' THEN COALESCE(published_at,CURRENT_TIMESTAMP(6)) ELSE published_at END,
						  updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND version=?
						""",
				article.state().name(), article.visibility().name(), article.version(),
				nullableBytes(article.publishedVersionId()), article.state().name(), bytes(article.publicId()),
				article.version() - 1);
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "内容版本已变化");
	}

	private Article restore(Parent parent) {
		var versions = jdbc.query(
				"SELECT * FROM tn_article_version WHERE article_id=(SELECT id FROM tn_article WHERE public_id=?) ORDER BY revision",
				this::version, bytes(parent.publicId));
		var pending = versions.stream().filter(value -> value.state() == VersionState.PENDING_REVIEW)
				.map(ArticleVersion::publicId).findFirst().orElse(null);
		return Article.restore(parent.publicId, parent.ownerId, parent.visibility, versions, parent.state, pending,
				parent.publishedVersionId, parent.version);
	}
	private ArticleVersion version(ResultSet result, int row) throws SQLException {
		return ArticleVersion.restore(uuid(result.getBytes("public_id")), result.getInt("revision"),
				result.getString("title"), result.getString("summary"), result.getString("body"),
				VersionState.valueOf(result.getString("state")));
	}
	private void upsertVersion(UUID articleId, ArticleVersion version) {
		var changed = jdbc.update(
				"UPDATE tn_article_version SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=?",
				version.state().name(), bytes(version.publicId()));
		if (changed == 0)
			insertVersion(articleId, version);
	}
	private void insertVersion(UUID articleId, ArticleVersion version) {
		jdbc.update(
				"""
						INSERT INTO tn_article_version(public_id,article_id,revision,title,summary,body,state,checksum,created_at,updated_at)
						SELECT ?,id,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_article WHERE public_id=?
						""",
				bytes(version.publicId()), version.revision(), version.title(), version.summary(), version.body(),
				version.state().name(), checksum(version.title(), version.summary(), version.body()), bytes(articleId));
	}
	private static Parent parent(ResultSet result) throws SQLException {
		return new Parent(uuid(result.getBytes("public_id")), uuid(result.getBytes("owner_public_id")),
				ContentState.valueOf(result.getString("state")), Visibility.valueOf(result.getString("visibility")),
				result.getLong("version"), nullableUuid(result.getBytes("published_public_id")));
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
	private record Parent(UUID publicId, UUID ownerId, ContentState state, Visibility visibility, long version,
			UUID publishedVersionId) {
	}
}
