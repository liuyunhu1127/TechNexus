package com.technexus.demand.api;

import com.technexus.demand.domain.Demand;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DemandRepository {
	Optional<Demand> findById(UUID publicId);
	List<Demand> listPublic(int limit);
	void add(Demand demand);
	void save(Demand demand, UUID actorId, String reason);
}
