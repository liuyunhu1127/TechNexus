package com.technexus.community.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Tag extends AggregateRoot {
	private final UUID publicId;
	private final String normalizedName;
	private final LinkedHashSet<String> aliases;
	private boolean enabled;
	private UUID mergedInto;

	public Tag(UUID publicId, String name) {
		this(publicId, name, true, Set.of(), null, 0);
	}
	public Tag(UUID publicId, String name, Collection<String> aliases) {
		this(publicId, name, true, aliases, null, 0);
	}

	private Tag(UUID publicId, String name, boolean enabled, Collection<String> aliases, UUID mergedInto,
			long version) {
		this.publicId = Objects.requireNonNull(publicId);
		this.normalizedName = normalize(name);
		this.aliases = normalizeAliases(aliases, normalizedName);
		this.enabled = enabled;
		this.mergedInto = mergedInto;
		restoreVersion(version);
	}

	public static Tag restore(UUID publicId, String name, boolean enabled, Collection<String> aliases, UUID mergedInto,
			long version) {
		return new Tag(publicId, name, enabled, aliases, mergedInto, version);
	}

	public void replaceAliases(Collection<String> values) {
		ensureActive();
		var normalized = normalizeAliases(values, normalizedName);
		if (aliases.equals(normalized))
			return;
		aliases.clear();
		aliases.addAll(normalized);
		changed();
	}

	public void setEnabled(boolean value) {
		ensureActive();
		if (enabled == value)
			return;
		enabled = value;
		changed();
	}

	public void mergeInto(Tag target) {
		Objects.requireNonNull(target);
		ensureActive();
		if (publicId.equals(target.publicId))
			throw new DomainException("TAG_SELF_MERGE", "标签不能合并到自身");
		if (target.mergedInto != null)
			throw new DomainException("TAG_TARGET_MERGED", "目标标签已合并");
		var targetAliases = new LinkedHashSet<>(target.aliases);
		targetAliases.add(normalizedName);
		targetAliases.addAll(aliases);
		target.replaceAliases(targetAliases);
		mergedInto = target.publicId;
		enabled = false;
		changed();
	}

	private void ensureActive() {
		if (mergedInto != null)
			throw new DomainException("TAG_ALREADY_MERGED", "已合并标签不能修改");
	}

	private static String normalize(String value) {
		var result = Objects.requireNonNull(value).trim().toLowerCase(Locale.ROOT);
		if (result.isEmpty() || result.length() > 48)
			throw new DomainException("TAG_NAME_INVALID", "标签名长度无效");
		return result;
	}
	private static LinkedHashSet<String> normalizeAliases(Collection<String> values, String canonicalName) {
		if (values == null)
			return new LinkedHashSet<>();
		if (values.size() > 20)
			throw new DomainException("TAG_ALIAS_LIMIT", "标签别名不能超过 20 个");
		var result = new LinkedHashSet<String>();
		for (var value : values) {
			var alias = normalize(value);
			if (canonicalName.equals(alias))
				throw new DomainException("TAG_ALIAS_CANONICAL_CONFLICT", "别名不能与标准名相同");
			if (!result.add(alias))
				throw new DomainException("TAG_ALIAS_DUPLICATE", "标签别名不能重复");
		}
		return result;
	}

	public UUID publicId() {
		return publicId;
	}
	public String normalizedName() {
		return normalizedName;
	}
	public Set<String> aliases() {
		return Set.copyOf(aliases);
	}
	public boolean enabled() {
		return enabled;
	}
	public UUID mergedInto() {
		return mergedInto;
	}
}
