package com.technexus.server.community.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.UuidV7;
import com.technexus.community.api.CommentRepository;
import com.technexus.community.api.NotificationRepository;
import com.technexus.community.domain.Comment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CommunityApplicationService {
	private final CommentRepository comments;
	private final NotificationRepository notifications;
	public CommunityApplicationService(CommentRepository comments, NotificationRepository notifications) {
		this.comments = comments;
		this.notifications = notifications;
	}

	public CommentView createComment(UUID authorId, UUID contentId, UUID parentId, String body) {
		var comment = new Comment(UuidV7.generate(), authorId, new TargetRef("CONTENT", contentId), parentId, body);
		comments.add(comment);
		return new CommentView(comment.publicId(), comment.body(), comment.state().name(), Instant.now());
	}

	@Transactional(readOnly = true)
	public List<NotificationItem> listNotifications(UUID recipientId) {
		return notifications.listFor(recipientId, 50).stream().map(value -> new NotificationItem(value.publicId(),
				value.type(), value.message(), value.readAt(), value.createdAt())).toList();
	}

	public void readNotification(UUID recipientId, UUID notificationId) {
		if (!notifications.markRead(recipientId, notificationId, Instant.now())) {
			throw new DomainException("RESOURCE_NOT_FOUND", "通知不存在");
		}
	}

	public record CommentView(UUID id, String body, String state, Instant createdAt) {
	}
	public record NotificationItem(UUID id, String type, String title, Instant readAt, Instant createdAt) {
	}
}
