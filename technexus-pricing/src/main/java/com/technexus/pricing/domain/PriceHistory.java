package com.technexus.pricing.domain;

import com.technexus.common.domain.Money;
import java.time.Instant;
import java.util.UUID;

public record PriceHistory(Money before, Money after, UUID operatorId, String reason, Instant changedAt) {
}
