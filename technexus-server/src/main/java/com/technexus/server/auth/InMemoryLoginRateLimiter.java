package com.technexus.server.auth;

import com.technexus.common.domain.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class InMemoryLoginRateLimiter implements LoginRateLimiter {
	private static final int MAX_ATTEMPTS = 10;
	private static final long WINDOW_SECONDS = 300;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	private final Clock clock;

	public InMemoryLoginRateLimiter() {
		this(Clock.systemUTC());
	}

	InMemoryLoginRateLimiter(Clock clock) {
		this.clock = clock;
	}

	@Override
	public void acquire(String account, String clientAddress) {
		var now = clock.instant();
		var key = account + "|" + clientAddress;
		var updated = windows.compute(key, (ignored, current) -> {
			if (current == null || !current.resetAt.isAfter(now))
				return new Window(1, now.plusSeconds(WINDOW_SECONDS));
			return new Window(current.attempts + 1, current.resetAt);
		});
		if (updated.attempts > MAX_ATTEMPTS)
			throw new DomainException("AUTH_RATE_LIMITED", "登录尝试过于频繁，请稍后重试");
	}

	private record Window(int attempts, Instant resetAt) {
	}
}
