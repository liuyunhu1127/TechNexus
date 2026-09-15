package com.technexus.community.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class Tag extends AggregateRoot {
	private final UUID publicId;
	private final String normalizedName;
	private UUID mergedInto;

	public Tag(UUID publicId, String name) {
		this.publicId = Objects.requireNonNull(publicId);
		this.normalizedName = normalize(name);
	}

	public void mergeInto(Tag target) {
		Objects.requireNonNull(target);
		if (publicId.equals(target.publicId))
			throw new DomainException("TAG_SELF_MERGE", "标签不能合并到自身");
		if (target.mergedInto != null)
			throw new DomainException("TAG_TARGET_MERGED", "目标标签已合并");
		mergedInto = target.publicId;
		changed();
	}

	private static String normalize(String value) {
		var result = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
		if (result.isEmpty() || result.length() > 48)
			throw new DomainException("TAG_NAME_INVALID", "标签名长度无效");
		return result;
	}

	public UUID publicId() {
		return publicId;
	}
	public String normalizedName() {
		return normalizedName;
	}
	public UUID mergedInto() {
		return mergedInto;
	}
}
