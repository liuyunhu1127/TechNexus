package com.technexus.server.content.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.content.application.port.ContentAssociationPort;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcContentAssociationAdapter implements ContentAssociationPort {
	private final JdbcTemplate jdbc;

	public JdbcContentAssociationAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void replace(UUID contentId, UUID ownerId, String contentType, List<UUID> tagIds, List<UUID> fileIds) {
		var target = target(contentType);
		requireOwner(contentId, ownerId, target);
		syncTargetVisibility(contentId, target);
		if (tagIds != null)
			replaceTags(contentId, target, unique(tagIds, 10));
		if (fileIds != null)
			replaceFiles(contentId, ownerId, target, unique(fileIds, 20));
	}

	@Override
	public ContentAssociations get(UUID contentId, String contentType) {
		var target = target(contentType);
		var tagIds = jdbc.query("""
				SELECT t.public_id FROM %s link
				JOIN %s content ON content.id=link.%s
				JOIN tn_tag t ON t.id=link.tag_id
				WHERE content.public_id=? ORDER BY t.normalized_name
				""".formatted(target.linkTable(), target.contentTable(), target.contentForeignKey()),
				(result, row) -> uuid(result.getBytes(1)), bytes(contentId));
		var fileIds = jdbc.query("""
				SELECT f.public_id FROM tn_file_relation r
				JOIN tn_file_object f ON f.id=r.file_object_id
				WHERE r.target_type='CONTENT' AND r.target_public_id=?
				ORDER BY r.id
				""", (result, row) -> uuid(result.getBytes(1)), bytes(contentId));
		return new ContentAssociations(List.copyOf(tagIds), List.copyOf(fileIds));
	}

	@Override
	public List<TagOption> listTags() {
		return jdbc.query("""
				SELECT public_id,normalized_name FROM tn_tag
				WHERE merged_into_id IS NULL AND enabled=TRUE ORDER BY normalized_name LIMIT 100
				""", (result, row) -> new TagOption(uuid(result.getBytes(1)), result.getString(2)));
	}

	private void requireOwner(UUID contentId, UUID ownerId, Target target) {
		var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM %s content JOIN tn_user u ON u.id=content.owner_id
				WHERE content.public_id=? AND u.public_id=?
				""".formatted(target.contentTable()), Integer.class, bytes(contentId), bytes(ownerId));
		if (count == null || count != 1)
			throw new DomainException("RESOURCE_NOT_FOUND", "内容不存在");
	}

	private void replaceTags(UUID contentId, Target target, List<UUID> tagIds) {
		for (var tagId : tagIds) {
			var count = jdbc.queryForObject(
					"SELECT COUNT(*) FROM tn_tag WHERE public_id=? AND merged_into_id IS NULL AND enabled=TRUE",
					Integer.class, bytes(tagId));
			if (count == null || count != 1)
				throw new DomainException("TAG_NOT_FOUND", "标签不存在");
		}
		jdbc.update("DELETE FROM %s WHERE %s=(SELECT id FROM %s WHERE public_id=?)".formatted(target.linkTable(),
				target.contentForeignKey(), target.contentTable()), bytes(contentId));
		for (var tagId : tagIds) {
			jdbc.update("""
					INSERT INTO %s(%s,tag_id,created_at,updated_at)
					SELECT content.id,tag.id,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
					FROM %s content JOIN tn_tag tag ON tag.public_id=?
					WHERE content.public_id=?
					""".formatted(target.linkTable(), target.contentForeignKey(), target.contentTable()), bytes(tagId),
					bytes(contentId));
		}
	}

	private void syncTargetVisibility(UUID contentId, Target target) {
		jdbc.update("""
				UPDATE tn_file_relation r JOIN %s content ON content.public_id=r.target_public_id
				SET r.visibility=content.visibility,r.updated_at=CURRENT_TIMESTAMP(6),r.version=r.version+1
				WHERE r.target_type='CONTENT' AND r.target_public_id=?
				""".formatted(target.contentTable()), bytes(contentId));
	}

	private void replaceFiles(UUID contentId, UUID ownerId, Target target, List<UUID> fileIds) {
		jdbc.update("""
				UPDATE tn_file_relation r JOIN tn_user u ON u.id=r.owner_id
				SET r.target_type=NULL,r.target_public_id=NULL,r.updated_at=CURRENT_TIMESTAMP(6),r.version=r.version+1
				WHERE r.target_type='CONTENT' AND r.target_public_id=? AND u.public_id=?
				""", bytes(contentId), bytes(ownerId));
		for (var fileId : fileIds) {
			var changed = jdbc.update("""
					UPDATE tn_file_relation r
					JOIN tn_file_object f ON f.id=r.file_object_id
					JOIN tn_user u ON u.id=r.owner_id
					JOIN %s content ON content.public_id=?
					SET r.target_type='CONTENT',r.target_public_id=?,r.visibility=content.visibility,
					    r.updated_at=CURRENT_TIMESTAMP(6),r.version=r.version+1
					WHERE f.public_id=? AND f.state='AVAILABLE' AND u.public_id=?
					  AND r.target_public_id IS NULL
					""".formatted(target.contentTable()), bytes(contentId), bytes(contentId), bytes(fileId),
					bytes(ownerId));
			if (changed != 1)
				throw new DomainException("FILE_NOT_AVAILABLE", "文件不存在、尚未通过扫描或已绑定其他内容");
		}
	}

	private static <T> List<T> unique(List<T> values, int maximum) {
		var result = new ArrayList<>(new LinkedHashSet<>(values));
		if (result.size() != values.size())
			throw new DomainException("CONTENT_ASSOCIATION_DUPLICATE", "标签或文件不能重复");
		if (result.size() > maximum)
			throw new DomainException("CONTENT_ASSOCIATION_LIMIT", "标签或文件数量超过限制");
		return List.copyOf(result);
	}

	private static Target target(String contentType) {
		return "DOCUMENT".equals(contentType)
				? new Target("tn_article", "tn_article_tag", "article_id")
				: new Target("tn_post", "tn_post_tag", "post_id");
	}

	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}

	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}

	private record Target(String contentTable, String linkTable, String contentForeignKey) {
	}
}
