package com.technexus.demand.api;

import com.technexus.common.domain.TargetRef;
import com.technexus.demand.domain.Lineage;
import java.util.List;

public interface LineageRepository {
	void add(Lineage lineage);
	List<Lineage> findConnected(TargetRef target);
}
