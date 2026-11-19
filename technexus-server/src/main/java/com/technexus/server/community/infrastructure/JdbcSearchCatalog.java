package com.technexus.server.community.infrastructure;

import com.technexus.server.community.application.port.SearchCatalog;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcSearchCatalog implements SearchCatalog {
	private final JdbcTemplate jdbc;
	public JdbcSearchCatalog(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	@Override
	public List<SearchItem> search(String query, String type, int fetchLimit) {
		var result = new ArrayList<SearchItem>();
		var like = "%" + query + "%";
		if ("ALL".equals(type) || "CONTENT".equals(type)) {
			result.addAll(jdbc.query(
					"""
							SELECT 'CONTENT' item_type,a.public_id,v.title,COALESCE(v.summary,'') summary,a.published_at,
							       a.pinned_rank,NULL budget_max,NULL deadline_at,
							       CASE WHEN LOWER(v.title)=LOWER(?) THEN 100 WHEN LOWER(v.title) LIKE LOWER(?) THEN 70 ELSE 30 END relevance
							FROM tn_article a JOIN tn_article_version v ON v.id=a.published_version_id
							WHERE a.state='PUBLISHED' AND a.visibility='PUBLIC' AND (v.title LIKE ? OR v.summary LIKE ?) LIMIT ?
							""",
					this::item, query, query + "%", like, like, fetchLimit));
			result.addAll(jdbc.query(
					"""
							SELECT 'CONTENT' item_type,p.public_id,v.title,COALESCE(v.summary,'') summary,p.published_at,
							       p.pinned_rank,NULL budget_max,NULL deadline_at,
							       CASE WHEN LOWER(v.title)=LOWER(?) THEN 100 WHEN LOWER(v.title) LIKE LOWER(?) THEN 70 ELSE 30 END relevance
							FROM tn_post p JOIN tn_post_version v ON v.id=p.published_version_id
							WHERE p.state='PUBLISHED' AND p.visibility='PUBLIC' AND (v.title LIKE ? OR v.body LIKE ?) LIMIT ?
							""",
					this::item, query, query + "%", like, like, fetchLimit));
		}
		if ("ALL".equals(type) || "DEMAND".equals(type))
			result.addAll(jdbc.query(
					"""
							SELECT 'DEMAND' item_type,public_id,title,description summary,created_at published_at,NULL pinned_rank,
							       budget_max,deadline_at,
							       CASE WHEN LOWER(title)=LOWER(?) THEN 100 WHEN LOWER(title) LIKE LOWER(?) THEN 70 ELSE 30 END relevance
							FROM tn_demand WHERE state IN ('PUBLISHED','NEGOTIATING','CONFIRMED','IN_PROGRESS','DELIVERED','COMPLETED')
							AND visibility='PUBLIC' AND (title LIKE ? OR description LIKE ?) LIMIT ?
							""",
					this::item, query, query + "%", like, like, fetchLimit));
		if ("ALL".equals(type) || "USER".equals(type))
			result.addAll(jdbc.query(
					"""
							SELECT 'USER' item_type,public_id,display_name title,'' summary,created_at published_at,NULL pinned_rank,
							       NULL budget_max,NULL deadline_at,
							       CASE WHEN LOWER(display_name)=LOWER(?) THEN 100 WHEN LOWER(display_name) LIKE LOWER(?) THEN 70 ELSE 30 END relevance
							FROM tn_user WHERE status='ACTIVE' AND display_name LIKE ? LIMIT ?
							""",
					this::item, query, query + "%", like, fetchLimit));
		if ("ALL".equals(type) || "TAG".equals(type))
			result.addAll(jdbc.query(
					"""
							SELECT 'TAG' item_type,public_id,normalized_name title,'' summary,created_at published_at,NULL pinned_rank,
							       NULL budget_max,NULL deadline_at,
							       CASE WHEN LOWER(normalized_name)=LOWER(?) THEN 100 WHEN LOWER(normalized_name) LIKE LOWER(?) THEN 70 ELSE 30 END relevance
							FROM tn_tag WHERE enabled=TRUE AND merged_into_id IS NULL AND normalized_name LIKE ? LIMIT ?
							""",
					this::item, query, query + "%", like, fetchLimit));
		return result;
	}
	private SearchItem item(ResultSet result, int row) throws SQLException {
		return new SearchItem(result.getString("item_type"), uuid(result.getBytes("public_id")),
				result.getString("title"), result.getString("summary"), result.getTimestamp("published_at").toInstant(),
				(Integer) result.getObject("pinned_rank"), result.getBigDecimal("budget_max"),
				result.getTimestamp("deadline_at") == null ? null : result.getTimestamp("deadline_at").toInstant(),
				result.getInt("relevance"));
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
