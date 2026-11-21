package com.technexus.server.user.infrastructure;

import com.technexus.server.user.application.EmailActionStore;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryEmailActionStore implements EmailActionStore {
	private final Map<Key, Action> actions = new ConcurrentHashMap<>();

	@Override
	public void replace(Action action) {
		actions.put(new Key(action.userId(), action.kind()), action);
	}

	@Override
	public Optional<Action> lock(UUID id, Kind kind) {
		return actions.values().stream().filter(action -> action.id().equals(id) && action.kind() == kind).findFirst();
	}

	@Override
	public boolean consume(UUID id) {
		for (var entry : actions.entrySet()) {
			var action = entry.getValue();
			if (action.id().equals(id))
				return actions.remove(entry.getKey(), action);
		}
		return false;
	}

	private record Key(UUID userId, Kind kind) {
	}
}
