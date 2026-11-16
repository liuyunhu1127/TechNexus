package com.technexus.server.community.infrastructure;

import com.technexus.server.community.application.port.SearchCatalog;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemorySearchCatalog implements SearchCatalog {
	@Override
	public List<SearchItem> search(String query, String type, int fetchLimit) {
		return List.of();
	}
}
