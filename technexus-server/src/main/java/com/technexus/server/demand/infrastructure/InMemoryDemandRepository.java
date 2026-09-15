package com.technexus.server.demand.infrastructure;

import com.technexus.demand.api.DemandRepository;
import com.technexus.demand.domain.Demand;
import com.technexus.demand.domain.DemandState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryDemandRepository implements DemandRepository {
	private final Map<UUID, Demand> values = new ConcurrentHashMap<>();
	@Override
	public Optional<Demand> findById(UUID id) {
		return Optional.ofNullable(values.get(id));
	}
	@Override
	public List<Demand> listPublic(int limit) {
		return values.values().stream().filter(value -> value.state() == DemandState.PUBLISHED).limit(limit).toList();
	}
	@Override
	public void add(Demand demand) {
		values.put(demand.publicId(), demand);
	}
	@Override
	public void save(Demand demand, UUID actorId, String reason) {
		values.put(demand.publicId(), demand);
	}
}
