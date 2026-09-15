package com.technexus.pricing.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PriceObject extends AggregateRoot {
	private final UUID publicId;
	private final TargetRef target;
	private PricingMode mode = PricingMode.FREE;
	private Money amount;
	private Instant validFrom;
	private final List<PriceHistory> history = new ArrayList<>();

	public PriceObject(TargetRef target) {
		this(UUID.randomUUID(), target);
	}

	public PriceObject(UUID publicId, TargetRef target) {
		this.publicId = Objects.requireNonNull(publicId);
		this.target = Objects.requireNonNull(target);
	}

	public static PriceObject restore(UUID publicId, TargetRef target, PricingMode mode, Money amount,
			Instant validFrom, List<PriceHistory> history, long version) {
		var price = new PriceObject(publicId, target);
		price.mode = Objects.requireNonNull(mode);
		price.amount = amount;
		price.validFrom = validFrom;
		price.history.addAll(history);
		price.restoreVersion(version);
		return price;
	}

	public void confirm(PricingMode newMode, Money newAmount, UUID operatorId, String reason, Instant now) {
		confirm(newMode, newAmount, now, operatorId, reason, now);
	}

	public void confirm(PricingMode newMode, Money newAmount, Instant newValidFrom, UUID operatorId, String reason,
			Instant now) {
		Objects.requireNonNull(newMode);
		if (newMode == PricingMode.FREE && newAmount != null)
			throw new DomainException("FREE_PRICE_HAS_AMOUNT", "免费模式不能有金额");
		if (newMode != PricingMode.FREE && newAmount == null)
			throw new DomainException("PRICE_REQUIRED", "收费模式必须有金额");
		if (reason == null || reason.isBlank() || reason.length() > 1000) {
			throw new DomainException("PRICE_REASON_INVALID", "定价原因长度无效");
		}
		history.add(new PriceHistory(amount, newAmount, Objects.requireNonNull(operatorId), reason,
				Objects.requireNonNull(now)));
		mode = newMode;
		amount = newAmount;
		validFrom = Objects.requireNonNull(newValidFrom);
		changed();
	}

	public UUID publicId() {
		return publicId;
	}
	public TargetRef target() {
		return target;
	}
	public PricingMode mode() {
		return mode;
	}
	public Money amount() {
		return amount;
	}
	public Instant validFrom() {
		return validFrom;
	}
	public List<PriceHistory> history() {
		return List.copyOf(history);
	}
}
