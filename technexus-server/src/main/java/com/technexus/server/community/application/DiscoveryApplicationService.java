package com.technexus.server.community.application;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.AdminApplicationService;
import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.demand.application.DemandApplicationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscoveryApplicationService {
	private final AdminApplicationService admin;
	private final ContentApplicationService contents;
	private final DemandApplicationService demands;
	public DiscoveryApplicationService(AdminApplicationService admin, ContentApplicationService contents,
			DemandApplicationService demands) {
		this.admin = admin;
		this.contents = contents;
		this.demands = demands;
	}
	@Transactional(readOnly = true)
	public List<RecommendationView> recommendations(int limit) {
		var result = new ArrayList<RecommendationView>();
		var seen = new HashSet<String>();
		for (var item : admin.getRecommendationConfig().items().stream()
				.sorted(java.util.Comparator.comparingInt(AdminApplicationService.RecommendationItem::rank)).toList()) {
			add(result, seen, item.targetType(), item.targetId(), item.rank(), true);
		}
		var rank = 1000;
		for (var content : contents.listPublic()) {
			if (result.size() >= limit)
				break;
			if (seen.add("CONTENT:" + content.id()))
				result.add(new RecommendationView("CONTENT", content.id(), rank++, false, content));
		}
		for (var demand : demands.listPublic()) {
			if (result.size() >= limit)
				break;
			if (seen.add("DEMAND:" + demand.id()))
				result.add(new RecommendationView("DEMAND", demand.id(), rank++, false, demand));
		}
		return result.stream().limit(Math.min(Math.max(limit, 1), 100)).toList();
	}
	private void add(List<RecommendationView> result, HashSet<String> seen, String type, UUID id, int rank,
			boolean operated) {
		if (!seen.add(type + ":" + id))
			return;
		try {
			var data = switch (type) {
				case "CONTENT" -> contents.get(id, null);
				case "DEMAND" -> demands.get(id, null);
				default -> null;
			};
			if (data != null)
				result.add(new RecommendationView(type, id, rank, operated, data));
		} catch (DomainException ignored) {
			seen.remove(type + ":" + id);
		}
	}
	public record RecommendationView(String targetType, UUID targetId, int rank, boolean operated, Object data) {
	}
}
