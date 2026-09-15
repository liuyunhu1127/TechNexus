package com.technexus.server.admin.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.server.admin.application.port.AiSuggestionProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "technexus.ai.enabled", havingValue = "true")
public class OpenAiCompatibleSuggestionProvider implements AiSuggestionProvider {
	private final RestClient client;
	private final ObjectMapper json;
	private final String model;

	public OpenAiCompatibleSuggestionProvider(RestClient.Builder builder, ObjectMapper json,
			@Value("${technexus.ai.base-url}") String baseUrl, @Value("${technexus.ai.api-key}") String apiKey,
			@Value("${technexus.ai.model}") String model) {
		this.client = builder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
		this.json = json;
		this.model = model;
	}

	@Override
	public Map<String, Object> generate(String kind, String targetType, UUID targetId, UUID targetVersionId) {
		var instruction = "Return one JSON object only. This is an advisory suggestion and must never mutate or publish data.";
		var input = "kind=%s,targetType=%s,targetId=%s,targetVersionId=%s".formatted(kind, targetType, targetId,
				targetVersionId);
		var response = client.post().uri("/chat/completions").body(Map.of("model", model, "temperature", 0,
				"response_format", Map.of("type", "json_object"), "messages",
				List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", input))))
				.retrieve().body(ChatResponse.class);
		try {
			if (response == null || response.choices() == null || response.choices().isEmpty()
					|| response.choices().getFirst().message() == null) {
				throw new IllegalStateException("AI provider response has no choice");
			}
			return json.readValue(response.choices().getFirst().message().content(), new TypeReference<>() {
			});
		} catch (Exception error) {
			throw new IllegalStateException("AI provider response is invalid", error);
		}
	}

	private record ChatResponse(List<Choice> choices) {
	}

	private record Choice(Message message) {
	}

	private record Message(String content) {
	}
}
