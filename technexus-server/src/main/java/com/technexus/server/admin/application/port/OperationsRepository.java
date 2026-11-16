package com.technexus.server.admin.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface OperationsRepository {
	Optional<ConfigRecord> findConfig(String key);
	ConfigRecord saveConfig(String key, Object value, long expectedVersion, UUID actorId, String reason);
	List<FeatureFlagRecord> listFeatureFlags();
	Optional<FeatureFlagRecord> findFeatureFlag(String key);
	FeatureFlagRecord saveFeatureFlag(String key, boolean enabled, Object rule, long expectedVersion, UUID actorId,
			String reason);
	AiSuggestionRecord addSuggestion(AiSuggestionRecord suggestion);
	Optional<AiSuggestionRecord> findSuggestion(UUID id);
	List<AiSuggestionRecord> listSuggestions(String state, int limit);
	Optional<AiSuggestionJob> claimNextSuggestion(Instant now, Instant leaseUntil);
	void completeSuggestion(UUID id, long expectedVersion, Map<String, Object> output);
	void retrySuggestion(UUID id, long expectedVersion, Instant retryAt, String error);
	void failSuggestion(UUID id, long expectedVersion, String error);
	AiSuggestionRecord decideSuggestion(UUID id, String decision, Map<String, Object> editedOutput,
			long expectedVersion, UUID actorId);
	List<OperationAuditRecord> listOperationAudits(String action, int limit);
	DashboardRecord dashboard();

	record ConfigRecord(String key, Object value, long version) {
	}
	record FeatureFlagRecord(String key, boolean enabled, Object rule, long version) {
	}
	record AiSuggestionRecord(UUID id, String kind, String targetType, UUID targetId, UUID targetVersionId,
			String state, boolean isSuggestion, Map<String, Object> output, long version) {
	}
	record AiSuggestionJob(UUID id, String kind, String targetType, UUID targetId, UUID targetVersionId, int attempt,
			long version) {
	}
	record OperationAuditRecord(long id, UUID actorId, String action, Map<String, Object> target, Instant createdAt) {
	}
	record DashboardRecord(long pendingAudit, long pendingPricing, long failedJobs, boolean storageAlert,
			boolean backupAlert) {
	}
}
