package com.technexus.common.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record TargetRef(String type, UUID publicId) {
	public TargetRef {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(publicId, "publicId");
		type = type.trim().toUpperCase(Locale.ROOT);
		if (type.isEmpty())
			throw new DomainException("TARGET_TYPE_REQUIRED", "目标类型不能为空");
	}
}
