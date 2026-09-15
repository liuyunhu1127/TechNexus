package com.technexus.content.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Post extends AggregateRoot {
	private final UUID publicId;
	private final UUID ownerId;
	private final PostType type;
	private final List<PostVersion> versions = new ArrayList<>();
	private Visibility visibility;
	private ContentState state = ContentState.DRAFT;
	private UUID pendingVersionId;
	private UUID publishedVersionId;

	public Post(UUID publicId, UUID ownerId, PostType type, Visibility visibility, PostVersion initialVersion) {
		this.publicId = Objects.requireNonNull(publicId);
		this.ownerId = Objects.requireNonNull(ownerId);
		this.type = Objects.requireNonNull(type);
		this.visibility = Objects.requireNonNull(visibility);
		versions.add(Objects.requireNonNull(initialVersion));
	}

	public static Post restore(UUID publicId, UUID ownerId, PostType type, Visibility visibility,
			List<PostVersion> versions, ContentState state, UUID pendingVersionId, UUID publishedVersionId,
			long version) {
		if (versions.isEmpty())
			throw new DomainException("CONTENT_VERSION_MISSING", "内容版本缺失");
		var post = new Post(publicId, ownerId, type, visibility, versions.getFirst());
		post.versions.clear();
		post.versions.addAll(versions);
		post.state = Objects.requireNonNull(state);
		post.pendingVersionId = pendingVersionId;
		post.publishedVersionId = publishedVersionId;
		post.restoreVersion(version);
		return post;
	}

	public PostVersion revise(UUID actorId, UUID versionId, String title, String body, Visibility visibility) {
		requireOwner(actorId);
		if (pendingVersionId != null)
			throw new DomainException("CONTENT_REVIEW_PENDING", "已有待审版本");
		var revision = new PostVersion(versionId, versions.size() + 1, title, body);
		versions.add(revision);
		this.visibility = Objects.requireNonNull(visibility);
		changed();
		return revision;
	}

	public UUID submit(UUID actorId) {
		requireOwner(actorId);
		var candidate = versions.get(versions.size() - 1);
		candidate.submit();
		pendingVersionId = candidate.publicId();
		state = ContentState.PENDING_REVIEW;
		changed();
		return pendingVersionId;
	}

	public void applyReview(UUID reviewedVersionId, boolean approved) {
		if (!Objects.equals(pendingVersionId, reviewedVersionId))
			throw new DomainException("AUDIT_TARGET_VERSION_CONFLICT", "审核目标版本已变化");
		var version = versions.stream().filter(item -> item.publicId().equals(reviewedVersionId)).findFirst()
				.orElseThrow();
		if (approved) {
			version.approve();
			publishedVersionId = reviewedVersionId;
			state = ContentState.PUBLISHED;
		} else {
			version.reject();
			state = publishedVersionId == null ? ContentState.REJECTED : ContentState.PUBLISHED;
		}
		pendingVersionId = null;
		changed();
	}

	private void requireOwner(UUID actorId) {
		if (!ownerId.equals(actorId))
			throw new DomainException("CONTENT_FORBIDDEN", "只能由内容所有者修改");
	}

	public UUID publicId() {
		return publicId;
	}
	public UUID ownerId() {
		return ownerId;
	}
	public PostType type() {
		return type;
	}
	public Visibility visibility() {
		return visibility;
	}
	public ContentState state() {
		return state;
	}
	public UUID publishedVersionId() {
		return publishedVersionId;
	}
	public UUID pendingVersionId() {
		return pendingVersionId;
	}
	public List<PostVersion> versions() {
		return List.copyOf(versions);
	}
}
