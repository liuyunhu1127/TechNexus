package com.technexus.server.community.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import com.technexus.community.api.TagRepository;
import com.technexus.community.domain.Tag;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcTagRepository implements TagRepository {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	public JdbcTagRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.json = json;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public List<Tag> list(int limit) {
		return jdbc.query("""
				SELECT t.public_id,t.normalized_name,t.enabled,m.public_id AS merged_into_public_id,t.version
				FROM tn_tag t LEFT JOIN tn_tag m ON m.id=t.merged_into_id
				ORDER BY t.normalized_name LIMIT ?
				""", (result, row) -> restore(result.getBytes("public_id"), result.getString("normalized_name"),
				result.getBoolean("enabled"), result.getBytes("merged_into_public_id"), result.getLong("version")),
				limit);
	}

	@Override
	public Optional<Tag> findById(UUID id) {
		return jdbc.query("""
				SELECT t.public_id,t.normalized_name,t.enabled,m.public_id AS merged_into_public_id,t.version
				FROM tn_tag t LEFT JOIN tn_tag m ON m.id=t.merged_into_id WHERE t.public_id=?
				""", (result, row) -> restore(result.getBytes("public_id"), result.getString("normalized_name"),
				result.getBoolean("enabled"), result.getBytes("merged_into_public_id"), result.getLong("version")),
				bytes(id)).stream().findFirst();
	}

	@Override
	public void add(Tag tag, UUID actorId, String reason) {
		transactions.executeWithoutResult(status -> {
			requireNamesAvailable(tag, Set.of());
			jdbc.update("""
					INSERT INTO tn_tag(public_id,normalized_name,merged_into_id,enabled,version,created_at,updated_at)
					VALUES(?,?,NULL,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
					""", bytes(tag.publicId()), tag.normalizedName(), tag.enabled());
			replaceAliases(tag);
			audit(actorId, "TAG_CREATE", Map.of("tagId", tag.publicId().toString(), "reason", reason));
		});
	}

	@Override
	public void save(Tag tag, long expectedVersion, UUID actorId, String reason) {
		transactions.executeWithoutResult(status -> {
			requireNamesAvailable(tag, Set.of(tag.publicId()));
			var changed = jdbc.update("""
					UPDATE tn_tag SET enabled=?,version=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=? AND merged_into_id IS NULL
					""", tag.enabled(), tag.version(), bytes(tag.publicId()), expectedVersion);
			if (changed != 1)
				throw new DomainException("VERSION_CONFLICT", "标签版本已变化");
			replaceAliases(tag);
			audit(actorId, "TAG_UPDATE", Map.of("tagId", tag.publicId().toString(), "reason", reason));
		});
	}

	@Override
	public void merge(Tag source, Tag target, long expectedSourceVersion, long expectedTargetVersion, UUID actorId,
			String reason) {
		transactions.executeWithoutResult(status -> {
			requireNamesAvailable(target, Set.of(source.publicId(), target.publicId()));
			migrateRelations("tn_article_tag", "article_id", source.publicId(), target.publicId());
			migrateRelations("tn_post_tag", "post_id", source.publicId(), target.publicId());
			var targetChanged = jdbc.update("""
					UPDATE tn_tag SET enabled=?,version=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=? AND merged_into_id IS NULL
					""", target.enabled(), target.version(), bytes(target.publicId()), expectedTargetVersion);
			var sourceChanged = jdbc.update(
					"""
							UPDATE tn_tag SET merged_into_id=(SELECT target.id FROM (SELECT id FROM tn_tag WHERE public_id=?) target),
							                  enabled=FALSE,version=?,updated_at=CURRENT_TIMESTAMP(6)
							WHERE public_id=? AND version=? AND merged_into_id IS NULL
							""",
					bytes(target.publicId()), source.version(), bytes(source.publicId()), expectedSourceVersion);
			if (targetChanged != 1 || sourceChanged != 1)
				throw new DomainException("VERSION_CONFLICT", "标签版本已变化");
			jdbc.update("DELETE a FROM tn_tag_alias a JOIN tn_tag t ON t.id=a.tag_id WHERE t.public_id=?",
					bytes(source.publicId()));
			replaceAliases(target);
			audit(actorId, "TAG_MERGE", Map.of("sourceTagId", source.publicId().toString(), "targetTagId",
					target.publicId().toString(), "reason", reason));
		});
	}

	private Tag restore(byte[] id, String name, boolean enabled, byte[] mergedInto, long version) {
		var publicId = uuid(id);
		var aliases = jdbc.query("""
				SELECT a.alias_name FROM tn_tag_alias a JOIN tn_tag t ON t.id=a.tag_id
				WHERE t.public_id=? ORDER BY a.alias_name
				""", (result, row) -> result.getString(1), id);
		return Tag.restore(publicId, name, enabled, aliases, nullableUuid(mergedInto), version);
	}

	private void replaceAliases(Tag tag) {
		jdbc.update("DELETE a FROM tn_tag_alias a JOIN tn_tag t ON t.id=a.tag_id WHERE t.public_id=?",
				bytes(tag.publicId()));
		for (var alias : tag.aliases())
			jdbc.update("""
					INSERT INTO tn_tag_alias(alias_name,tag_id,created_at,updated_at)
					SELECT ?,id,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_tag WHERE public_id=?
					""", alias, bytes(tag.publicId()));
	}

	private void requireNamesAvailable(Tag candidate, Set<UUID> allowed) {
		var names = new LinkedHashSet<String>();
		names.add(candidate.normalizedName());
		names.addAll(candidate.aliases());
		for (var name : names) {
			var canonicalOwners = jdbc.query("SELECT public_id FROM tn_tag WHERE normalized_name=?",
					(result, row) -> uuid(result.getBytes(1)), name);
			var aliasOwners = jdbc.query("""
					SELECT t.public_id FROM tn_tag_alias a JOIN tn_tag t ON t.id=a.tag_id WHERE a.alias_name=?
					""", (result, row) -> uuid(result.getBytes(1)), name);
			if (canonicalOwners.stream().anyMatch(id -> !allowed.contains(id))
					|| aliasOwners.stream().anyMatch(id -> !allowed.contains(id)))
				throw new DomainException("TAG_NAME_CONFLICT", "标签标准名或别名已存在");
		}
	}

	private void migrateRelations(String table, String ownerColumn, UUID sourceId, UUID targetId) {
		jdbc.update("""
				DELETE source FROM %s source
				JOIN tn_tag source_tag ON source_tag.id=source.tag_id
				JOIN tn_tag target_tag ON target_tag.public_id=?
				JOIN %s target ON target.%s=source.%s AND target.tag_id=target_tag.id
				WHERE source_tag.public_id=?
				""".formatted(table, table, ownerColumn, ownerColumn), bytes(targetId), bytes(sourceId));
		jdbc.update("""
				UPDATE %s link JOIN tn_tag source_tag ON source_tag.id=link.tag_id
				JOIN tn_tag target_tag ON target_tag.public_id=?
				SET link.tag_id=target_tag.id,link.updated_at=CURRENT_TIMESTAMP(6)
				WHERE source_tag.public_id=?
				""".formatted(table), bytes(targetId), bytes(sourceId));
	}

	private void audit(UUID actorId, String action, Map<String, Object> target) {
		try {
			var changed = jdbc.update("""
					INSERT INTO tn_operation_audit(actor_id,action,target,created_at,updated_at)
					SELECT id,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
					""", action, json.writeValueAsString(target), bytes(actorId));
			if (changed != 1)
				throw new DomainException("AUTH_REQUIRED", "操作人不存在");
		} catch (DomainException error) {
			throw error;
		} catch (Exception error) {
			throw new IllegalStateException("Could not write tag audit", error);
		}
	}

	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
	private static UUID nullableUuid(byte[] value) {
		return value == null ? null : uuid(value);
	}
}
