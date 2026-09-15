package com.technexus.server.auth;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.UuidV7;
import com.technexus.server.auth.application.port.AuthAccountStore;
import com.technexus.server.auth.application.port.AuthAccountStore.StoredAccount;
import com.technexus.server.security.BearerTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthApplicationService {
	private final AuthAccountStore store;
	private final PasswordEncoder passwords;
	private final BearerTokenService tokens;
	private final LoginRateLimiter loginRateLimiter;
	private final String bootstrapAdminEmail;
	private final Clock clock = Clock.systemUTC();

	public AuthApplicationService(AuthAccountStore store, PasswordEncoder passwords, BearerTokenService tokens,
			LoginRateLimiter loginRateLimiter,
			@Value("${technexus.security.bootstrap-admin-email:}") String bootstrapAdminEmail) {
		this.store = store;
		this.passwords = passwords;
		this.tokens = tokens;
		this.loginRateLimiter = loginRateLimiter;
		this.bootstrapAdminEmail = bootstrapAdminEmail.trim().toLowerCase(Locale.ROOT);
	}

	public Account register(String email, String password, String displayName) {
		var normalized = email.trim().toLowerCase(Locale.ROOT);
		if (!normalized.contains("@"))
			throw new DomainException("EMAIL_INVALID", "邮箱格式无效");
		if (password == null || password.length() < 12)
			throw new DomainException("PASSWORD_WEAK", "密码至少 12 位");
		var resolvedName = displayName == null || displayName.isBlank()
				? normalized.substring(0, normalized.indexOf('@'))
				: displayName.trim();
		var account = store.createAccount(UuidV7.generate(), normalized, passwords.encode(password), resolvedName);
		return view(account);
	}

	public TokenPair login(String email, String password, String clientAddress) {
		var normalized = email.trim().toLowerCase(Locale.ROOT);
		loginRateLimiter.acquire(normalized, clientAddress);
		var now = clock.instant();
		if (store.isLoginLocked(normalized, now))
			throw new DomainException("AUTH_INVALID", "邮箱或密码错误");
		var account = store.findByEmail(normalized).orElse(null);
		if (account == null || !passwords.matches(password, account.passwordHash())) {
			store.recordLoginFailure(normalized, now, 5, now.plusSeconds(900));
			throw new DomainException("AUTH_INVALID", "邮箱或密码错误");
		}
		store.recordLoginSuccess(normalized);
		return newSession(account, UuidV7.generate());
	}

	public TokenPair refresh(String rawRefreshToken) {
		var raw = newRefreshToken();
		var sid = UuidV7.generate();
		var expiry = clock.instant().plusSeconds(30L * 24 * 3600);
		var rotation = store.rotateSession(hash(rawRefreshToken), sid, hash(raw), expiry);
		return switch (rotation.status()) {
			case ROTATED -> tokenPair(rotation.account(), sid, raw, expiry);
			case REUSED -> throw new DomainException("AUTH_REFRESH_REUSED", "检测到刷新令牌重用，令牌族已撤销");
			case EXPIRED -> throw new DomainException("REFRESH_EXPIRED", "刷新令牌已过期");
			case INVALID -> throw new DomainException("REFRESH_INVALID", "刷新令牌无效");
		};
	}

	public void logout(String rawRefreshToken) {
		if (rawRefreshToken != null)
			store.revokeSession(hash(rawRefreshToken));
	}

	private TokenPair newSession(StoredAccount account, UUID family) {
		var sid = UuidV7.generate();
		var rawRefresh = newRefreshToken();
		var expiry = clock.instant().plusSeconds(30L * 24 * 3600);
		store.createSession(account, sid, family, hash(rawRefresh), expiry);
		return tokenPair(account, sid, rawRefresh, expiry);
	}

	private TokenPair tokenPair(StoredAccount account, UUID sid, String rawRefresh, Instant expiry) {
		return new TokenPair(tokens.issue(account.publicId(), sid, account.sessionVersion(), isAdministrator(account)),
				rawRefresh, 900, expiry);
	}

	private boolean isAdministrator(StoredAccount account) {
		return account.normalizedEmail().equals(bootstrapAdminEmail);
	}

	private static String newRefreshToken() {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((UuidV7.generate() + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private Account view(StoredAccount account) {
		return new Account(account.publicId(), account.normalizedEmail(), null, account.displayName(),
				isAdministrator(account), account.sessionVersion());
	}

	public record Account(UUID publicId, String email, String passwordHash, String displayName, boolean administrator,
			long sessionVersion) {
	}
	public record TokenPair(String accessToken, String refreshToken, long expiresIn, Instant refreshExpiresAt) {
	}
}
