package com.technexus.common.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UuidV7Test {
	@Test
	void generatesVersionSevenVariantTwoUuid() {
		var id = UuidV7.generate();
		assertEquals(7, id.version());
		assertEquals(2, id.variant());
	}
}
