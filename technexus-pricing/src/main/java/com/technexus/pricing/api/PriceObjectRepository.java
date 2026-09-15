package com.technexus.pricing.api;

import com.technexus.common.domain.TargetRef;
import com.technexus.pricing.domain.PriceObject;
import java.util.Optional;

public interface PriceObjectRepository {
	Optional<PriceObject> findByTarget(TargetRef target);
	void add(PriceObject price);
	void save(PriceObject price, long expectedVersion);
	long countPending();
}
