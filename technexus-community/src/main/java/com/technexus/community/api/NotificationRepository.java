package com.technexus.community.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository {
	void add(NotificationView notification);
	List<NotificationView> listFor(UUID recipientId, int limit);
	boolean markRead(UUID recipientId, UUID notificationId, Instant readAt);
}
