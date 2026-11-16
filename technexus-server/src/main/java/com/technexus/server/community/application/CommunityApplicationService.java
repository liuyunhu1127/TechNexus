package com.technexus.server.community.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.UuidV7;
import com.technexus.community.api.CommentRepository;
import com.technexus.community.api.NotificationRepository;
import com.technexus.community.api.NotificationView;
import com.technexus.community.api.TagRepository;
import com.technexus.community.domain.Comment;
import com.technexus.community.domain.Tag;
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
	private final TagRepository tags;
	public CommunityApplicationService(CommentRepository comments, NotificationRepository notifications,
			TagRepository tags) {
		this.comments = comments;
		this.notifications = notifications;
		this.tags = tags;
	}

	public CommentView createComment(UUID authorId, UUID contentId, UUID parentId, String body) {
		var comment = new Comment(UuidV7.generate(), authorId, new TargetRef("CONTENT", contentId), parentId, body);
		comments.add(comment);
		var createdAt = Instant.now();
		comments.findInteractionRecipient(contentId, parentId).filter(recipient -> !recipient.equals(authorId))
				.ifPresent(recipient -> notifications
						.add(new NotificationView(UuidV7.generate(), recipient, parentId == null ? "COMMENT" : "REPLY",
								parentId == null ? "你的内容收到了新评论" : "你的评论收到了新回复", createdAt, null)));
		return new CommentView(comment.publicId(), authorId, parentId, comment.body(), comment.state().name(),
				comment.version(), createdAt);
	}

	@Transactional(readOnly = true)
	public List<CommentView> listComments(UUID contentId) {
		return comments.listFor("CONTENT", contentId, 200).stream().map(CommunityApplicationService::commentView)
				.toList();
	}

	public CommentView deleteComment(UUID actorId, UUID contentId, UUID commentId, long expectedVersion) {
		var current = comments.findById(commentId)
				.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "评论不存在"));
		if (!current.authorId().equals(actorId))
			throw new DomainException("COMMENT_FORBIDDEN", "只能删除自己的评论");
		if (current.version() != expectedVersion)
			throw new DomainException("VERSION_CONFLICT", "评论版本已变化");
		if (!comments.softDelete(commentId, contentId, actorId, expectedVersion, Instant.now()))
			throw new DomainException("COMMENT_NOT_EDITABLE", "评论不可删除");
		return new CommentView(current.id(), current.authorId(), current.parentId(), "", "DELETED",
				current.version() + 1, current.createdAt());
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

	@Transactional(readOnly = true)
	public List<TagView> listTags() {
		return tags.list(100).stream().map(CommunityApplicationService::tagView).toList();
	}

	@Transactional(readOnly = true)
	public TagView getTag(UUID tagId) {
		return tags.findById(tagId).map(CommunityApplicationService::tagView)
				.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "标签不存在"));
	}

	public TagView createTag(UUID actorId, TagCreateCommand command) {
		requireReason(command.reason());
		var tag = new Tag(UuidV7.generate(), command.name(), command.aliases());
		tags.add(tag, actorId, command.reason());
		return tagView(tag);
	}

	public TagView updateTag(UUID tagId, UUID actorId, TagUpdateCommand command) {
		requireReason(command.reason());
		var tag = tags.findById(tagId).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "标签不存在"));
		requireVersion(tag.version(), command.expectedVersion());
		tag.setEnabled(command.enabled());
		tag.replaceAliases(command.aliases());
		tags.save(tag, command.expectedVersion(), actorId, command.reason());
		return tagView(tag);
	}

	public TagMergeView mergeTag(UUID sourceTagId, UUID actorId, TagMergeCommand command) {
		requireReason(command.reason());
		var source = tags.findById(sourceTagId).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "源标签不存在"));
		var target = tags.findById(command.targetTagId())
				.orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "目标标签不存在"));
		requireVersion(source.version(), command.expectedSourceVersion());
		requireVersion(target.version(), command.expectedTargetVersion());
		source.mergeInto(target);
		tags.merge(source, target, command.expectedSourceVersion(), command.expectedTargetVersion(), actorId,
				command.reason());
		return new TagMergeView(tagView(source), tagView(target));
	}

	private static TagView tagView(Tag tag) {
		return new TagView(tag.publicId(), tag.normalizedName(), tag.aliases().stream().sorted().toList(),
				tag.enabled(), tag.mergedInto(), tag.version());
	}
	private static void requireReason(String reason) {
		if (reason == null || reason.isBlank() || reason.length() > 1000)
			throw new DomainException("TAG_REASON_INVALID", "标签变更原因无效");
	}
	private static void requireVersion(long actual, long expected) {
		if (actual != expected)
			throw new DomainException("VERSION_CONFLICT", "标签版本已变化");
	}

	private static CommentView commentView(CommentRepository.CommentItem value) {
		return new CommentView(value.id(), value.authorId(), value.parentId(), value.body(), value.state(),
				value.version(), value.createdAt());
	}

	public record CommentView(UUID id, UUID authorId, UUID parentId, String body, String state, long version,
			Instant createdAt) {
	}
	public record NotificationItem(UUID id, String type, String title, Instant readAt, Instant createdAt) {
	}
	public record TagCreateCommand(String name, List<String> aliases, String reason) {
	}
	public record TagUpdateCommand(boolean enabled, List<String> aliases, long expectedVersion, String reason) {
	}
	public record TagMergeCommand(UUID targetTagId, long expectedSourceVersion, long expectedTargetVersion,
			String reason) {
	}
	public record TagView(UUID id, String name, List<String> aliases, boolean enabled, UUID mergedIntoId,
			long version) {
	}
	public record TagMergeView(TagView source, TagView target) {
	}
}
