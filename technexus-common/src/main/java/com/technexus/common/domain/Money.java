package com.technexus.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {
	private static final Currency CNY = Currency.getInstance("CNY");

	public Money {
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(currency, "currency");
		if (amount.signum() < 0)
			throw new DomainException("MONEY_NEGATIVE", "金额不能为负数");
		amount = amount.setScale(2, RoundingMode.UNNECESSARY);
	}

	public static Money cny(BigDecimal amount) {
		return new Money(amount, CNY);
	}

	@Override
	public int compareTo(Money other) {
		if (!currency.equals(other.currency))
			throw new DomainException("CURRENCY_MISMATCH", "币种不同");
		return amount.compareTo(other.amount);
	}
}
