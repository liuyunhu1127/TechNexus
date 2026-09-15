package com.technexus.server.admin.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.admin.application.port.OperationsRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryOperationsRepository implements OperationsRepository {
	private final ConcurrentHashMap<String, ConfigRecord> configs = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, AiSuggestionRecord> suggestions = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<UUID, Integer> attempts = new ConcurrentHashMap<>();

	@Override
	public Optional<ConfigRecord> findConfig(String key) {
		return Optional.ofNullable(configs.get(key));
	}
	@Override
	public ConfigRecord saveConfig(String key, Object value, long expectedVersion, UUID actorId, String reason) {
		return configs.compute(key, (ignored, current) -> {
			var actual = current == null ? 0 : current.version();
			if (actual != expectedVersion)
				throw new DomainException("VERSION_CONFLICT", "配置版本已变化");
			return new ConfigRecord(key, value, actual + 1);
		});
	}
	@Override
	public AiSuggestionRecord addSuggestion(AiSuggestionRecord suggestion) {
		if (suggestions.putIfAbsent(suggestion.id(), suggestion) != null)
			throw new DomainException("AI_SUGGESTION_EXISTS", "AI 建议已存在");
		return suggestion;
	}
	@Override
	public Optional<AiSuggestionRecord> findSuggestion(UUID id) {
		return Optional.ofNullable(suggestions.get(id));
	}
	@Override
	public Optional<AiSuggestionJob> claimNextSuggestion(Instant now, Instant leaseUntil) {
		for (var entry : suggestions.entrySet()) {
			var claimed = new java.util.concurrent.atomic.AtomicReference<AiSuggestionJob>();
			suggestions.computeIfPresent(entry.getKey(), (id, current) -> {
				if (!"QUEUED".equals(current.state()))
					return current;
				var attempt = attempts.merge(id, 1, Integer::sum);
				var next = new AiSuggestionRecord(current.id(), current.kind(), current.targetType(),
						current.targetId(), current.targetVersionId(), "RUNNING", true, current.output(),
						current.version() + 1);
				claimed.set(new AiSuggestionJob(id, current.kind(), current.targetType(), current.targetId(),
						current.targetVersionId(), attempt, next.version()));
				return next;
			});
			if (claimed.get() != null)
				return Optional.of(claimed.get());
		}
		return Optional.empty();
	}
	@Override
	public void completeSuggestion(UUID id, long expectedVersion, Map<String, Object> output) {
		transition(id, expectedVersion, "SUCCEEDED", output);
	}
	@Override
	public void retrySuggestion(UUID id, long expectedVersion, Instant retryAt, String error) {
		transition(id, expectedVersion, "QUEUED", Map.of());
	}
	@Override
	public void failSuggestion(UUID id, long expectedVersion, String error) {
		transition(id, expectedVersion, "FAILED", Map.of());
	}
	private void transition(UUID id, long expectedVersion, String state, Map<String, Object> output) {
		suggestions.compute(id, (ignored, current) -> {
			if (current == null)
				throw new DomainException("RESOURCE_NOT_FOUND", "AI 建议不存在");
			if (current.version() != expectedVersion || !"RUNNING".equals(current.state()))
				throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
			return new AiSuggestionRecord(current.id(), current.kind(), current.targetType(), current.targetId(),
					current.targetVersionId(), state, true, output, current.version() + 1);
		});
	}
	@Override
	public AiSuggestionRecord decideSuggestion(UUID id, String decision, Map<String, Object> editedOutput,
			long expectedVersion, UUID actorId) {
		return suggestions.compute(id, (ignored, current) -> {
			if (current == null)
				throw new DomainException("RESOURCE_NOT_FOUND", "AI 建议不存在");
			if (current.version() != expectedVersion)
				throw new DomainException("VERSION_CONFLICT", "AI 建议版本已变化");
			if (!"SUCCEEDED".equals(current.state()))
				throw new DomainException("AI_SUGGESTION_NOT_READY", "AI 建议尚未完成");
			var state = Map.of("ACCEPT", "ACCEPTED", "EDIT", "EDITED", "REJECT", "REJECTED").get(decision);
			var output = "EDIT".equals(decision) ? editedOutput : current.output();
			return new AiSuggestionRecord(current.id(), current.kind(), current.targetType(), current.targetId(),
					current.targetVersionId(), state, true, output, current.version() + 1);
		});
	}
	@Override
	public DashboardRecord dashboard() {
		return new DashboardRecord(0, 0, 0, false, true);
	}
}
