package com.technexus.community.api;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(UUID publicId, UUID recipientId, String type, String message, Instant createdAt,
		Instant readAt) {
}
