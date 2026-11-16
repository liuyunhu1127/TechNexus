package com.technexus.server.admin.application;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.port.OperationsRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeatureFlagEvaluator {
	private final OperationsRepository operations;

	public FeatureFlagEvaluator(OperationsRepository operations) {
		this.operations = operations;
	}

	@Transactional(readOnly = true)
	public boolean enabled(String key, String subject) {
		var flag = operations.findFeatureFlag(key).orElse(null);
		if (flag == null)
			return true;
		if (!flag.enabled())
			return false;
		var percentage = percentage(flag.rule());
		return percentage >= 100 || bucket(key, subject) < percentage;
	}

	public void requireEnabled(String key, String subject) {
		if (!enabled(key, subject))
			throw new DomainException("FEATURE_DISABLED", "该功能当前未开放");
	}

	private static int percentage(Object rule) {
		if (!(rule instanceof Map<?, ?> map) || !(map.get("percentage") instanceof Number number))
			return 100;
		return Math.min(100, Math.max(0, number.intValue()));
	}

	private static int bucket(String key, String subject) {
		try {
			var digest = MessageDigest.getInstance("SHA-256")
					.digest((key + ":" + subject).getBytes(StandardCharsets.UTF_8));
			return Byte.toUnsignedInt(digest[0]) * 100 / 256;
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}
}
