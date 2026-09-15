package com.technexus.user.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.SimpleDomainEvent;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class User extends AggregateRoot {
	private final UUID publicId;
	private final String normalizedEmail;
	private UserStatus status;
	private String displayName;
	private String bio;
	private long sessionVersion;

	public User(UUID publicId, String normalizedEmail, String displayName) {
		this.publicId = Objects.requireNonNull(publicId);
		this.normalizedEmail = requireEmail(normalizedEmail);
		this.displayName = requireDisplayName(displayName);
		this.bio = "";
		this.status = UserStatus.REGISTERED;
	}

	public void activate() {
		requireNotCancelled();
		if (status != UserStatus.REGISTERED && status != UserStatus.LOCKED) {
			throw new DomainException("USER_TRANSITION_INVALID", "当前状态不能激活");
		}
		status = UserStatus.ACTIVE;
		changed();
		raise(SimpleDomainEvent.now("UserActivated", Map.of("userId", publicId.toString())));
	}

	public void lock() {
		requireNotCancelled();
		status = UserStatus.LOCKED;
		revokeAllSessions();
	}

	public void disable() {
		requireNotCancelled();
		status = UserStatus.DISABLED;
		revokeAllSessions();
	}

	public void cancel() {
		requireNotCancelled();
		status = UserStatus.CANCELLED;
		revokeAllSessions();
		raise(SimpleDomainEvent.now("UserCancelled", Map.of("userId", publicId.toString())));
	}

	public void updateProfile(String newDisplayName) {
		updateProfile(newDisplayName, bio);
	}

	public void updateProfile(String newDisplayName, String newBio) {
		requireNotCancelled();
		displayName = requireDisplayName(newDisplayName);
		bio = requireBio(newBio);
		changed();
	}

	public static User restore(UUID publicId, String normalizedEmail, String displayName, String bio, UserStatus status,
			long sessionVersion, long version) {
		var user = new User(publicId, normalizedEmail, displayName);
		user.bio = requireBio(bio);
		user.status = Objects.requireNonNull(status);
		user.sessionVersion = sessionVersion;
		user.restoreVersion(version);
		return user;
	}

	private void revokeAllSessions() {
		sessionVersion++;
		changed();
		raise(SimpleDomainEvent.now("UserSessionsRevoked",
				Map.of("userId", publicId.toString(), "sessionVersion", sessionVersion)));
	}

	private void requireNotCancelled() {
		if (status == UserStatus.CANCELLED)
			throw new DomainException("USER_CANCELLED", "已注销用户不可变更");
	}

	private static String requireEmail(String email) {
		var value = Objects.requireNonNull(email).trim().toLowerCase();
		if (!value.contains("@") || value.length() > 254)
			throw new DomainException("EMAIL_INVALID", "邮箱格式无效");
		return value;
	}

	private static String requireDisplayName(String value) {
		var result = Objects.requireNonNull(value).trim();
		if (result.isEmpty() || result.length() > 80)
			throw new DomainException("DISPLAY_NAME_INVALID", "显示名长度无效");
		return result;
	}

	private static String requireBio(String value) {
		var result = value == null ? "" : value.trim();
		if (result.length() > 1_000)
			throw new DomainException("BIO_INVALID", "个人简介长度无效");
		return result;
	}

	public UUID publicId() {
		return publicId;
	}
	public String normalizedEmail() {
		return normalizedEmail;
	}
	public UserStatus status() {
		return status;
	}
	public String displayName() {
		return displayName;
	}
	public String bio() {
		return bio;
	}
	public long sessionVersion() {
		return sessionVersion;
	}
}
