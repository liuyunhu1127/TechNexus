package com.technexus.server.auth;

import com.technexus.common.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!test")
public class JdbcLoginRateLimiter implements LoginRateLimiter {
	private static final int MAX_ATTEMPTS = 10;
	private final JdbcTemplate jdbc;

	public JdbcLoginRateLimiter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public void acquire(String account, String clientAddress) {
		var bucket = hash(account.strip().toLowerCase(Locale.ROOT) + "|" + clientAddress);
		jdbc.update("""
				INSERT INTO tn_rate_limit(bucket_hash,attempt_count,reset_at,created_at,updated_at)
				VALUES (?,1,CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
				ON DUPLICATE KEY UPDATE
				  attempt_count=IF(reset_at<=CURRENT_TIMESTAMP(6),1,attempt_count+1),
				  reset_at=IF(reset_at<=CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE,reset_at),
				  updated_at=CURRENT_TIMESTAMP(6)
				""", bucket);
		var attempts = jdbc.queryForObject("SELECT attempt_count FROM tn_rate_limit WHERE bucket_hash=?", Integer.class,
				bucket);
		if (attempts != null && attempts > MAX_ATTEMPTS)
			throw new DomainException("AUTH_RATE_LIMITED", "登录尝试过于频繁，请稍后重试");
	}

	private static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
