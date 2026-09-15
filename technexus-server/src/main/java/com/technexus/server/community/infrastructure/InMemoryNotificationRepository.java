package com.technexus.server.community.infrastructure;

import com.technexus.community.api.NotificationRepository;
import com.technexus.community.api.NotificationView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryNotificationRepository implements NotificationRepository {
	@Override
	public List<NotificationView> listFor(UUID recipientId, int limit) {
		return List.of();
	}
	@Override
	public boolean markRead(UUID recipientId, UUID notificationId, Instant readAt) {
		return false;
	}
}
