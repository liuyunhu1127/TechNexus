package com.technexus.server.auth.infrastructure;

import java.nio.ByteBuffer;
import java.util.UUID;

final class UuidBinary {
	private UuidBinary() {
	}

	static byte[] toBytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}

	static UUID fromBytes(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
