package com.technexus.server.community.application;

import com.technexus.common.domain.DomainException;
import com.technexus.server.community.application.port.SearchCatalog;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchApplicationService {
	private final SearchCatalog catalog;
	public SearchApplicationService(SearchCatalog catalog) {
		this.catalog = catalog;
	}
	@Transactional(readOnly = true)
	public SearchPage search(String query, String type, String sort, int page, int size) {
		if (page < 0 || size < 1 || size > 100)
			throw new DomainException("SEARCH_PAGE_INVALID", "搜索分页参数无效");
		var normalizedType = type == null || type.isBlank() ? "ALL" : type.toUpperCase(java.util.Locale.ROOT);
		if (!List.of("ALL", "CONTENT", "DEMAND", "USER", "TAG").contains(normalizedType))
			throw new DomainException("SEARCH_TYPE_INVALID", "搜索类型无效");
		var normalizedSort = sort == null || sort.isBlank() ? "RELEVANCE" : sort.toUpperCase(java.util.Locale.ROOT);
		var all = catalog.search(query == null ? "" : query.trim(), normalizedType, 500).stream()
				.sorted(comparator(normalizedSort)).toList();
		var start = Math.min(all.size(), page * size);
		var end = Math.min(all.size(), start + size);
		return new SearchPage(all.subList(start, end), page, size, all.size(), end < all.size(), normalizedSort);
	}
	private static Comparator<SearchCatalog.SearchItem> comparator(String sort) {
		var newest = Comparator
				.comparing(SearchCatalog.SearchItem::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(SearchCatalog.SearchItem::id);
		return switch (sort) {
			case "RELEVANCE" ->
				Comparator.comparingInt(SearchCatalog.SearchItem::relevance).reversed().thenComparing(newest);
			case "LATEST" -> newest;
			case "HOT", "FAVORITES" -> Comparator
					.comparing(SearchCatalog.SearchItem::pinnedRank, Comparator.nullsLast(Comparator.naturalOrder()))
					.thenComparing(newest);
			case "BUDGET" -> Comparator
					.comparing(SearchCatalog.SearchItem::budgetMax, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(newest);
			case "DEADLINE" -> Comparator
					.comparing(SearchCatalog.SearchItem::deadlineAt, Comparator.nullsLast(Comparator.naturalOrder()))
					.thenComparing(newest);
			default -> throw new DomainException("SEARCH_SORT_INVALID", "搜索排序无效");
		};
	}
	public record SearchPage(List<SearchCatalog.SearchItem> items, int page, int size, int total, boolean hasNext,
			String sort) {
	}
}
