package com.technexus.content.api;

import com.technexus.content.domain.Article;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ArticleRepository {
	Optional<Article> findById(UUID publicId);
	List<Article> listPublished(int limit);
	void add(Article article);
	void save(Article article);
}
