package com.technexus.server.admin.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.pricing.api.PriceObjectRepository;
import com.technexus.pricing.domain.PriceObject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryPriceObjectRepository implements PriceObjectRepository {
	private final ConcurrentHashMap<TargetRef, PriceObject> values = new ConcurrentHashMap<>();
	@Override
	public Optional<PriceObject> findByTarget(TargetRef target) {
		return Optional.ofNullable(values.get(target));
	}
	@Override
	public void add(PriceObject price) {
		if (values.putIfAbsent(price.target(), price) != null)
			throw new DomainException("PRICE_EXISTS", "定价已存在");
	}
	@Override
	public void save(PriceObject price, long expectedVersion) {
		if (!values.containsKey(price.target()))
			throw new DomainException("VERSION_CONFLICT", "定价版本已变化");
		values.put(price.target(), price);
	}
	@Override
	public long countPending() {
		return 0;
	}
}
