package com.technexus.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
	@Test
	void acceptsTwoDecimalCny() {
		assertEquals("12.30", Money.cny(new BigDecimal("12.30")).amount().toPlainString());
	}

	@Test
	void rejectsNegativeAmount() {
		var error = assertThrows(DomainException.class, () -> Money.cny(new BigDecimal("-0.01")));
		assertEquals("MONEY_NEGATIVE", error.code());
	}

	@Test
	void rejectsExcessScaleAndCurrencyMismatch() {
		assertThrows(ArithmeticException.class, () -> Money.cny(new BigDecimal("1.001")));
		var cny = Money.cny(BigDecimal.ONE);
		var usd = new Money(BigDecimal.ONE, Currency.getInstance("USD"));
		var error = assertThrows(DomainException.class, () -> cny.compareTo(usd));
		assertEquals("CURRENCY_MISMATCH", error.code());
		assertEquals(0, cny.compareTo(Money.cny(BigDecimal.ONE)));
	}
}
