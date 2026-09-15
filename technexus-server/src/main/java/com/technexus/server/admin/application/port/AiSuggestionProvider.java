package com.technexus.server.admin.application.port;

import java.util.Map;
import java.util.UUID;

public interface AiSuggestionProvider {
	Map<String, Object> generate(String kind, String targetType, UUID targetId, UUID targetVersionId);
}
