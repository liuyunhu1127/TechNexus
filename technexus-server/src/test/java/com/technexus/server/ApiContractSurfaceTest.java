package com.technexus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest
@ActiveProfiles("test")
class ApiContractSurfaceTest {
	private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");
	private static final Set<String> NON_IDEMPOTENT_MUTATIONS = Set.of("POST /auth/login", "POST /auth/refresh");
	private static final Set<String> BODYLESS_MUTATIONS = Set.of("POST /auth/refresh", "POST /auth/logout",
			"POST /files/uploads/{uploadId}/complete", "POST /files/{fileId}/download-url",
			"POST /notifications/{notificationId}/read");
	private static final String IDEMPOTENCY_REF = "#/components/parameters/IdempotencyKey";

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void implementationMatchesTheCompleteV1ContractSurfaceAndSuccessStatuses() throws IOException {
		var actualStatuses = new HashMap<String, Integer>();
		mappings.getHandlerMethods().forEach((mapping, handler) -> addApiMappings(mapping, handler, actualStatuses));
		var contract = readAndValidateOpenApi();
		assertEquals(contract.successStatuses(), actualStatuses);
	}

	@SuppressWarnings("unchecked")
	private static Contract readAndValidateOpenApi() throws IOException {
		var path = Path.of("..", "docs", "technexus-solution-closure", "02-设计阶段", "openapi.yaml");
		Map<String, Object> document = new Yaml().load(Files.readString(path));
		assertEquals("3.1.0", document.get("openapi"));
		validateLocalReferences(document, document);

		var operationIds = new HashSet<String>();
		var successStatuses = new HashMap<String, Integer>();
		Map<String, Object> paths = map(document.get("paths"), "paths");
		paths.forEach((apiPath, pathValue) -> {
			Map<String, Object> pathItem = map(pathValue, apiPath);
			pathItem.forEach((method, operationValue) -> {
				if (!HTTP_METHODS.contains(method))
					return;
				var key = method.toUpperCase() + " " + apiPath;
				Map<String, Object> operation = map(operationValue, key);
				var operationId = (String) operation.get("operationId");
				assertNotNull(operationId, key + " must declare operationId");
				assertTrue(operationIds.add(operationId), "Duplicate operationId: " + operationId);

				Map<String, Object> responses = map(operation.get("responses"), key + " responses");
				var successCodes = responses.keySet().stream().filter(code -> code.matches("2\\d\\d"))
						.map(Integer::parseInt).sorted().toList();
				assertEquals(1, successCodes.size(), key + " must declare exactly one success response");
				var successCode = successCodes.getFirst();
				successStatuses.put(key, successCode);
				if (successCode != 204)
					assertJsonSchema(document, responses.get(Integer.toString(successCode)), key + " response");

				var mutation = Set.of("POST", "PUT", "PATCH", "DELETE").contains(method.toUpperCase());
				if (mutation && !NON_IDEMPOTENT_MUTATIONS.contains(key))
					assertTrue(hasParameterReference(operation, IDEMPOTENCY_REF),
							key + " must require Idempotency-Key");
				if (mutation && !BODYLESS_MUTATIONS.contains(key))
					assertJsonSchema(document, operation.get("requestBody"), key + " requestBody");
			});
		});
		assertEquals(29, paths.size());
		assertEquals(35, operationIds.size());
		return new Contract(successStatuses);
	}

	private static void addApiMappings(RequestMappingInfo mapping, HandlerMethod handler, Map<String, Integer> target) {
		for (var pattern : mapping.getPatternValues()) {
			if (!pattern.startsWith("/api/v1"))
				continue;
			var responseStatus = handler.getMethodAnnotation(ResponseStatus.class);
			var status = responseStatus == null ? 200 : responseStatus.code().value();
			for (var method : mapping.getMethodsCondition().getMethods()) {
				var key = method.name() + " " + pattern.substring(7);
				assertFalse(target.containsKey(key), "Duplicate controller mapping: " + key);
				target.put(key, status);
			}
		}
	}

	private static boolean hasParameterReference(Map<String, Object> operation, String expectedRef) {
		var parameters = operation.get("parameters");
		if (!(parameters instanceof List<?> values))
			return false;
		return values.stream().filter(Map.class::isInstance).map(Map.class::cast)
				.anyMatch(parameter -> expectedRef.equals(parameter.get("$ref")));
	}

	private static void assertJsonSchema(Map<String, Object> document, Object value, String location) {
		Map<String, Object> node = resolve(document, map(value, location));
		Map<String, Object> content = map(node.get("content"), location + " content");
		Map<String, Object> json = map(content.get("application/json"), location + " application/json");
		assertNotNull(json.get("schema"), location + " must declare a JSON schema");
	}

	private static void validateLocalReferences(Map<String, Object> document, Object value) {
		if (value instanceof Map<?, ?> values) {
			values.forEach((key, child) -> {
				if ("$ref".equals(key)) {
					assertTrue(child instanceof String && ((String) child).startsWith("#/"),
							"Only local OpenAPI references are allowed: " + child);
					resolveReference(document, (String) child);
				}
				validateLocalReferences(document, child);
			});
		} else if (value instanceof List<?> values) {
			values.forEach(child -> validateLocalReferences(document, child));
		}
	}

	private static Map<String, Object> resolve(Map<String, Object> document, Map<String, Object> value) {
		var reference = value.get("$ref");
		return reference instanceof String ref ? map(resolveReference(document, ref), ref) : value;
	}

	private static Object resolveReference(Map<String, Object> document, String reference) {
		Object current = document;
		for (var token : reference.substring(2).split("/")) {
			current = map(current, reference).get(token.replace("~1", "/").replace("~0", "~"));
			assertNotNull(current, "Broken OpenAPI reference: " + reference);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value, String location) {
		assertTrue(value instanceof Map<?, ?>, location + " must be an object");
		return (Map<String, Object>) value;
	}

	private record Contract(Map<String, Integer> successStatuses) {
	}
}
