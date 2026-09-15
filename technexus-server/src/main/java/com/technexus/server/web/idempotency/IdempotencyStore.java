package com.technexus.server.web.idempotency;

import java.util.UUID;

public interface IdempotencyStore {
	Reservation reserve(UUID actorPublicId, String key, String method, String path, byte[] requestHash);

	void complete(long reservationId, int responseStatus, String responseBody);

	void abandon(long reservationId);

	enum State {
		ACQUIRED, REPLAY, CONFLICT, IN_PROGRESS
	}

	record Reservation(State state, long id, Integer responseStatus, String responseBody) {
		public static Reservation acquired(long id) {
			return new Reservation(State.ACQUIRED, id, null, null);
		}

		public static Reservation replay(long id, int status, String body) {
			return new Reservation(State.REPLAY, id, status, body);
		}

		public static Reservation conflict(long id) {
			return new Reservation(State.CONFLICT, id, null, null);
		}

		public static Reservation inProgress(long id) {
			return new Reservation(State.IN_PROGRESS, id, null, null);
		}
	}
}
