package com.technexus.server.demand.infrastructure;

import com.technexus.demand.api.ProposalRepository;
import com.technexus.demand.domain.Proposal;
import com.technexus.demand.domain.ProposalState;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryProposalRepository implements ProposalRepository {
	private final Map<UUID, Proposal> values = new ConcurrentHashMap<>();
	@Override
	public Optional<Proposal> findById(UUID publicId) {
		return Optional.ofNullable(values.get(publicId));
	}
	@Override
	public void add(Proposal proposal) {
		values.put(proposal.publicId(), proposal);
	}
	@Override
	public void save(Proposal proposal) {
		values.put(proposal.publicId(), proposal);
	}
	@Override
	public boolean isAcceptedProvider(UUID demandId, UUID providerId) {
		return values.values().stream().anyMatch(value -> value.demandId().equals(demandId)
				&& value.providerId().equals(providerId) && value.state() == ProposalState.ACCEPTED);
	}
}
