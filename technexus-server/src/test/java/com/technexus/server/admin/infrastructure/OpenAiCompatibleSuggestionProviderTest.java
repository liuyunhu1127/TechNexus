package com.technexus.server.admin.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleSuggestionProviderTest {
	@Test
	void usesOpenAiCompatibleHttpBoundaryAndReturnsAdvisoryJsonOnly() throws Exception {
		var requestBody = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/chat/completions", exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			var response = """
					{"choices":[{"message":{"content":"{\\\"summary\\\":\\\"advisory only\\\"}"}}]}
					""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			var provider = new OpenAiCompatibleSuggestionProvider(RestClient.builder(), new ObjectMapper(),
					"http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "contract-key", "contract-model");
			var output = provider.generate("SUMMARY", "CONTENT", UUID.randomUUID(), UUID.randomUUID());
			assertEquals("advisory only", output.get("summary"));
			assertEquals("Bearer contract-key", authorization.get());
			assertTrue(requestBody.get().contains("contract-model"));
			assertTrue(requestBody.get().contains("must never mutate or publish data"));
		} finally {
			server.stop(0);
		}
	}
}
