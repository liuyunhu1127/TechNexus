package com.technexus.server.demand.infrastructure;

import com.technexus.common.domain.TargetRef;
import com.technexus.demand.api.LineageRepository;
import com.technexus.demand.domain.Lineage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryLineageRepository implements LineageRepository {
	private final List<Lineage> values = new CopyOnWriteArrayList<>();
	@Override
	public void add(Lineage lineage) {
		values.add(lineage);
	}
	@Override
	public List<Lineage> findConnected(TargetRef target) {
		return values.stream().filter(value -> value.source().equals(target) || value.target().equals(target)).toList();
	}
}
