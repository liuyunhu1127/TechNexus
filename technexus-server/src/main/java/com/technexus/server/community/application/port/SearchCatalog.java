package com.technexus.server.community.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SearchCatalog {
	List<SearchItem> search(String query, String type, int fetchLimit);

	record SearchItem(String type, UUID id, String title, String summary, Instant publishedAt, Integer pinnedRank,
			java.math.BigDecimal budgetMax, Instant deadlineAt, int relevance) {
	}
}
