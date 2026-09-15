package com.technexus.common.domain;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7 {
	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	public static UUID generate() {
		return generate(Clock.systemUTC(), RANDOM.nextLong(), RANDOM.nextLong());
	}

	static UUID generate(Clock clock, long randomA, long randomB) {
		long millis = clock.millis() & 0xFFFFFFFFFFFFL;
		long most = (millis << 16) | 0x7000L | (randomA & 0x0FFFL);
		long least = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(most, least);
	}
}
