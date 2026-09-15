package com.technexus.demand.api;

import com.technexus.demand.domain.Proposal;
import java.util.Optional;
import java.util.UUID;

public interface ProposalRepository {
	Optional<Proposal> findById(UUID publicId);
	void add(Proposal proposal);
	void save(Proposal proposal);
	boolean isAcceptedProvider(UUID demandId, UUID providerId);
}
