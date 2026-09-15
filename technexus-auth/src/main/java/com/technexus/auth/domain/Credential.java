package com.technexus.auth.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.util.Objects;
import java.util.UUID;

public final class Credential extends AggregateRoot {
	private final UUID userId;
	private String passwordHash;
	private CredentialStatus status = CredentialStatus.ACTIVE;
	private int failedAttempts;

	public Credential(UUID userId, String passwordHash) {
		this.userId = Objects.requireNonNull(userId);
		this.passwordHash = requireHash(passwordHash);
	}

	public void replacePassword(String newHash) {
		requireActive();
		passwordHash = requireHash(newHash);
		failedAttempts = 0;
		changed();
	}

	public void recordFailure() {
		requireActive();
		failedAttempts++;
		changed();
	}

	public void disable() {
		status = CredentialStatus.DISABLED;
		changed();
	}

	private void requireActive() {
		if (status != CredentialStatus.ACTIVE)
			throw new DomainException("CREDENTIAL_DISABLED", "凭据已停用");
	}

	private static String requireHash(String value) {
		var hash = Objects.requireNonNull(value);
		if (!hash.startsWith("$argon2id$") || hash.length() < 32) {
			throw new DomainException("PASSWORD_HASH_INVALID", "必须保存 Argon2id Hash");
		}
		return hash;
	}

	public UUID userId() {
		return userId;
	}
	public String passwordHash() {
		return passwordHash;
	}
	public CredentialStatus status() {
		return status;
	}
	public int failedAttempts() {
		return failedAttempts;
	}
}
