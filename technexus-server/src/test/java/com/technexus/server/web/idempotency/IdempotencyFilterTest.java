package com.technexus.server.web.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class IdempotencyFilterTest {
	@Test
	void storesTheFirstResponseAndReplaysTheSameRequestWithoutExecutingItAgain() throws Exception {
		var store = new FakeStore();
		var filter = new IdempotencyFilter(store, new ObjectMapper());
		var executions = new AtomicInteger();

		var firstResponse = new MockHttpServletResponse();
		filter.doFilter(request("request-key-123", "{\"title\":\"one\"}"), firstResponse, (request, response) -> {
			executions.incrementAndGet();
			var httpResponse = (HttpServletResponse) response;
			httpResponse.setContentType("application/json");
			httpResponse.setStatus(201);
			httpResponse.getWriter().write("{\"data\":{\"id\":\"one\"}}");
		});
		assertEquals(201, firstResponse.getStatus());
		assertEquals(201, store.completedStatus);

		store.next = IdempotencyStore.Reservation.replay(7, 201, "{\"data\":{\"id\":\"one\"}}");
		var replayResponse = new MockHttpServletResponse();
		filter.doFilter(request("request-key-123", "{\"title\":\"one\"}"), replayResponse,
				(request, response) -> executions.incrementAndGet());
		assertEquals(1, executions.get());
		assertEquals(201, replayResponse.getStatus());
		assertEquals("true", replayResponse.getHeader("X-Idempotent-Replay"));
		assertEquals("{\"data\":{\"id\":\"one\"}}", replayResponse.getContentAsString());
	}

	@Test
	void rejectsMissingKeysAndConflictingRequests() throws Exception {
		var store = new FakeStore();
		var filter = new IdempotencyFilter(store, new ObjectMapper());
		var missingResponse = new MockHttpServletResponse();
		filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/contents"), missingResponse,
				(request, response) -> {
				});
		assertEquals(400, missingResponse.getStatus());
		assertEquals(0, store.reserveCount);

		store.next = IdempotencyStore.Reservation.conflict(8);
		var conflictResponse = new MockHttpServletResponse();
		filter.doFilter(request("request-key-456", "{\"title\":\"different\"}"), conflictResponse,
				(request, response) -> {
				});
		assertEquals(409, conflictResponse.getStatus());
	}

	private static MockHttpServletRequest request(String key, String body) {
		var request = new MockHttpServletRequest("POST", "/api/v1/contents");
		request.addHeader("Idempotency-Key", key);
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static final class FakeStore implements IdempotencyStore {
		private Reservation next = Reservation.acquired(7);
		private int reserveCount;
		private int completedStatus;

		@Override
		public Reservation reserve(UUID actorPublicId, String key, String method, String path, byte[] requestHash) {
			reserveCount++;
			return next;
		}

		@Override
		public void complete(long reservationId, int responseStatus, String responseBody) {
			completedStatus = responseStatus;
		}

		@Override
		public void abandon(long reservationId) {
		}
	}
}
