package com.technexus.server.community.infrastructure;

import com.technexus.community.api.NotificationRepository;
import com.technexus.community.api.NotificationView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryNotificationRepository implements NotificationRepository {
	private final List<NotificationView> values = new CopyOnWriteArrayList<>();
	@Override
	public void add(NotificationView notification) {
		values.add(notification);
	}
	@Override
	public List<NotificationView> listFor(UUID recipientId, int limit) {
		return values.reversed().stream().filter(value -> value.recipientId().equals(recipientId)).limit(limit)
				.toList();
	}
	@Override
	public boolean markRead(UUID recipientId, UUID notificationId, Instant readAt) {
		for (var index = 0; index < values.size(); index++) {
			var value = values.get(index);
			if (value.recipientId().equals(recipientId) && value.publicId().equals(notificationId)) {
				values.set(index, new NotificationView(value.publicId(), value.recipientId(), value.type(),
						value.message(), value.createdAt(), value.readAt() == null ? readAt : value.readAt()));
				return true;
			}
		}
		return false;
	}
}
