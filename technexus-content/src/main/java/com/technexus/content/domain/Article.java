package com.technexus.content.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.SimpleDomainEvent;
import com.technexus.common.domain.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class Article extends AggregateRoot {
	private final UUID publicId;
	private final UUID ownerId;
	private final List<ArticleVersion> versions = new ArrayList<>();
	private Visibility visibility;
	private ContentState state = ContentState.DRAFT;
	private UUID pendingVersionId;
	private UUID publishedVersionId;

	public Article(UUID publicId, UUID ownerId, Visibility visibility, ArticleVersion initialVersion) {
		this.publicId = Objects.requireNonNull(publicId);
		this.ownerId = Objects.requireNonNull(ownerId);
		this.visibility = Objects.requireNonNull(visibility);
		versions.add(Objects.requireNonNull(initialVersion));
	}

	public static Article restore(UUID publicId, UUID ownerId, Visibility visibility, List<ArticleVersion> versions,
			ContentState state, UUID pendingVersionId, UUID publishedVersionId, long version) {
		if (versions.isEmpty())
			throw new DomainException("CONTENT_VERSION_MISSING", "内容版本缺失");
		var article = new Article(publicId, ownerId, visibility, versions.getFirst());
		article.versions.clear();
		article.versions.addAll(versions);
		article.state = Objects.requireNonNull(state);
		article.pendingVersionId = pendingVersionId;
		article.publishedVersionId = publishedVersionId;
		article.restoreVersion(version);
		return article;
	}

	public ArticleVersion revise(UUID actorId, UUID versionId, String title, String summary, String body) {
		requireOwner(actorId);
		if (pendingVersionId != null)
			throw new DomainException("CONTENT_REVIEW_PENDING", "已有待审版本");
		var revision = new ArticleVersion(versionId, versions.size() + 1, title, summary, body);
		versions.add(revision);
		changed();
		return revision;
	}

	public ArticleVersion revise(UUID actorId, UUID versionId, String title, String summary, String body,
			Visibility visibility) {
		var revision = revise(actorId, versionId, title, summary, body);
		this.visibility = Objects.requireNonNull(visibility);
		return revision;
	}

	public UUID submit(UUID actorId) {
		requireOwner(actorId);
		var candidate = versions.get(versions.size() - 1);
		candidate.submit();
		pendingVersionId = candidate.publicId();
		state = ContentState.PENDING_REVIEW;
		changed();
		raise(SimpleDomainEvent.now("ArticleSubmitted",
				Map.of("articleId", publicId.toString(), "versionId", pendingVersionId.toString())));
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
			raise(SimpleDomainEvent.now("ArticlePublished",
					Map.of("articleId", publicId.toString(), "versionId", reviewedVersionId.toString())));
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
	public Visibility visibility() {
		return visibility;
	}
	public ContentState state() {
		return state;
	}
	public UUID pendingVersionId() {
		return pendingVersionId;
	}
	public UUID publishedVersionId() {
		return publishedVersionId;
	}
	public List<ArticleVersion> versions() {
		return List.copyOf(versions);
	}
}
