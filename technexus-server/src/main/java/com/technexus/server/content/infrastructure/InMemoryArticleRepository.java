package com.technexus.server.content.infrastructure;

import com.technexus.content.api.ArticleRepository;
import com.technexus.content.domain.Article;
import com.technexus.content.domain.ContentState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryArticleRepository implements ArticleRepository {
	private final Map<UUID, Article> values = new ConcurrentHashMap<>();
	@Override
	public Optional<Article> findById(UUID publicId) {
		return Optional.ofNullable(values.get(publicId));
	}
	@Override
	public List<Article> listPublished(int limit) {
		return values.values().stream().filter(value -> value.state() == ContentState.PUBLISHED).limit(limit).toList();
	}
	@Override
	public void add(Article article) {
		values.put(article.publicId(), article);
	}
	@Override
	public void save(Article article) {
		values.put(article.publicId(), article);
	}
}
