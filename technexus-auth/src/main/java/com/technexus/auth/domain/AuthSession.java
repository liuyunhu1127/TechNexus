package com.technexus.auth.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AuthSession extends AggregateRoot {
	private final UUID sid;
	private final UUID userId;
	private final UUID tokenFamily;
	private String refreshHash;
	private Instant expiresAt;
	private SessionStatus status = SessionStatus.ACTIVE;

	public AuthSession(UUID sid, UUID userId, UUID tokenFamily, String refreshHash, Instant expiresAt) {
		this.sid = Objects.requireNonNull(sid);
		this.userId = Objects.requireNonNull(userId);
		this.tokenFamily = Objects.requireNonNull(tokenFamily);
		this.refreshHash = requireHash(refreshHash);
		this.expiresAt = Objects.requireNonNull(expiresAt);
	}

	public AuthSession rotate(UUID nextSid, String nextRefreshHash, Instant nextExpiry, Instant now) {
		requireUsable(now);
		status = SessionStatus.ROTATED;
		changed();
		return new AuthSession(nextSid, userId, tokenFamily, requireHash(nextRefreshHash), nextExpiry);
	}

	public void revoke() {
		if (status == SessionStatus.ACTIVE) {
			status = SessionStatus.REVOKED;
			changed();
		}
	}

	public void requireUsable(Instant now) {
		if (status != SessionStatus.ACTIVE)
			throw new DomainException("SESSION_REUSED", "会话已轮换或撤销");
		if (!expiresAt.isAfter(now)) {
			status = SessionStatus.EXPIRED;
			throw new DomainException("SESSION_EXPIRED", "会话已过期");
		}
	}

	private static String requireHash(String value) {
		var result = Objects.requireNonNull(value);
		if (result.length() < 32)
			throw new DomainException("TOKEN_HASH_INVALID", "令牌 Hash 长度不足");
		return result;
	}

	public UUID sid() {
		return sid;
	}
	public UUID userId() {
		return userId;
	}
	public UUID tokenFamily() {
		return tokenFamily;
	}
	public String refreshHash() {
		return refreshHash;
	}
	public Instant expiresAt() {
		return expiresAt;
	}
	public SessionStatus status() {
		return status;
	}
}
