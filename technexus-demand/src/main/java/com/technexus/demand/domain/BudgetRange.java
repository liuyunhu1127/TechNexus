package com.technexus.demand.domain;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;

public record BudgetRange(Money minimum, Money maximum) {
	public BudgetRange {
		if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
			throw new DomainException("BUDGET_RANGE_INVALID", "最低预算不能高于最高预算");
		}
	}
}
