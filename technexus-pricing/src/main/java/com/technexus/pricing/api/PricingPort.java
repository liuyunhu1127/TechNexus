package com.technexus.pricing.api;

import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import com.technexus.pricing.domain.PricingMode;
import java.util.UUID;

public interface PricingPort {
	void confirm(TargetRef target, PricingMode mode, Money amount, UUID operatorId, String reason);
}
