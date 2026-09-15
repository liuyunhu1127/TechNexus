package com.technexus.community.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.util.Objects;
import java.util.UUID;

public final class Comment extends AggregateRoot {
	private final UUID publicId;
	private final UUID authorId;
	private final TargetRef target;
	private final UUID parentId;
	private String body;
	private CommentState state = CommentState.PUBLISHED;

	public Comment(UUID publicId, UUID authorId, TargetRef target, UUID parentId, String body) {
		this.publicId = Objects.requireNonNull(publicId);
		this.authorId = Objects.requireNonNull(authorId);
		this.target = Objects.requireNonNull(target);
		this.parentId = parentId;
		this.body = requireBody(body);
	}

	public void edit(UUID actorId, String newBody) {
		requireOwner(actorId);
		if (state != CommentState.PUBLISHED)
			throw new DomainException("COMMENT_NOT_EDITABLE", "评论不可编辑");
		body = requireBody(newBody);
		changed();
	}

	public void delete(UUID actorId) {
		requireOwner(actorId);
		body = "";
		state = CommentState.DELETED;
		changed();
	}

	public void block() {
		body = "";
		state = CommentState.BLOCKED;
		changed();
	}

	private void requireOwner(UUID actorId) {
		if (!authorId.equals(actorId))
			throw new DomainException("COMMENT_FORBIDDEN", "只能修改自己的评论");
	}

	private static String requireBody(String value) {
		var result = Objects.requireNonNull(value).trim();
		if (result.isEmpty() || result.length() > 5000)
			throw new DomainException("COMMENT_BODY_INVALID", "评论长度无效");
		return result;
	}

	public UUID publicId() {
		return publicId;
	}
	public UUID authorId() {
		return authorId;
	}
	public TargetRef target() {
		return target;
	}
	public UUID parentId() {
		return parentId;
	}
	public String body() {
		return body;
	}
	public CommentState state() {
		return state;
	}
}
