package com.technexus.user.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public final class Role extends AggregateRoot {
	private final String code;
	private final Set<String> permissions = new HashSet<>();

	public Role(String code, Collection<String> permissions) {
		this.code = normalize(code);
		permissions.forEach(this::grant);
	}

	public void grant(String permission) {
		if (permissions.add(normalize(permission)))
			changed();
	}

	public void revoke(String permission) {
		if (permissions.remove(normalize(permission)))
			changed();
	}

	private static String normalize(String value) {
		if (value == null || !value.matches("[a-z][a-z0-9_.:-]{1,63}")) {
			throw new DomainException("PERMISSION_INVALID", "角色或权限标识无效");
		}
		return value;
	}

	public String code() {
		return code;
	}
	public Set<String> permissions() {
		return Set.copyOf(permissions);
	}
}
