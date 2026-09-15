package com.technexus.server.admin.application;

import com.technexus.server.admin.application.port.AiSuggestionProvider;
import com.technexus.server.admin.application.port.OperationsRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "technexus.ai.enabled", havingValue = "true")
public class AiSuggestionWorker {
	private static final int MAX_ATTEMPTS = 5;
	private final OperationsRepository operations;
	private final AiSuggestionProvider provider;
	private final Clock clock;

	@Autowired
	public AiSuggestionWorker(OperationsRepository operations, AiSuggestionProvider provider) {
		this(operations, provider, Clock.systemUTC());
	}

	AiSuggestionWorker(OperationsRepository operations, AiSuggestionProvider provider, Clock clock) {
		this.operations = operations;
		this.provider = provider;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${technexus.ai.poll-ms:5000}")
	public void poll() {
		for (int count = 0; count < 10 && processNext(); count++) {
			// Keep one scheduler execution bounded.
		}
	}

	public boolean processNext() {
		var now = clock.instant();
		var candidate = operations.claimNextSuggestion(now, now.plusSeconds(120));
		if (candidate.isEmpty())
			return false;
		var job = candidate.get();
		try {
			var output = provider.generate(job.kind(), job.targetType(), job.targetId(), job.targetVersionId());
			if (output == null || output.isEmpty())
				throw new IllegalStateException("AI provider returned empty output");
			operations.completeSuggestion(job.id(), job.version(), output);
		} catch (Exception error) {
			var message = safe(error.getMessage());
			if (job.attempt() >= MAX_ATTEMPTS) {
				operations.failSuggestion(job.id(), job.version(), message);
			} else {
				var delay = Math.min(3600, 30L << Math.min(job.attempt() - 1, 6));
				operations.retrySuggestion(job.id(), job.version(), now.plusSeconds(delay), message);
			}
		}
		return true;
	}

	private static String safe(String value) {
		if (value == null || value.isBlank())
			return "UNKNOWN";
		return value.length() <= 900 ? value : value.substring(0, 900);
	}
}
