package com.technexus.content.domain;

import java.util.Objects;
import java.util.UUID;

public final class PostVersion {
	private final UUID publicId;
	private final int revision;
	private final String title;
	private final String body;
	private VersionState state = VersionState.DRAFT;

	public PostVersion(UUID publicId, int revision, String title, String body) {
		this.publicId = Objects.requireNonNull(publicId);
		if (revision < 1)
			throw new IllegalArgumentException("revision");
		this.revision = revision;
		this.title = ArticleVersion.requireText(title, 160, "标题");
		this.body = ArticleVersion.requireText(body, 200_000, "正文");
	}

	public static PostVersion restore(UUID publicId, int revision, String title, String body, VersionState state) {
		var version = new PostVersion(publicId, revision, title, body);
		version.state = Objects.requireNonNull(state);
		return version;
	}

	void submit() {
		if (state != VersionState.DRAFT)
			throw new IllegalStateException("not draft");
		state = VersionState.PENDING_REVIEW;
	}
	void approve() {
		if (state != VersionState.PENDING_REVIEW)
			throw new IllegalStateException("not pending");
		state = VersionState.APPROVED;
	}
	void reject() {
		if (state != VersionState.PENDING_REVIEW)
			throw new IllegalStateException("not pending");
		state = VersionState.REJECTED;
	}
	public UUID publicId() {
		return publicId;
	}
	public int revision() {
		return revision;
	}
	public String title() {
		return title;
	}
	public String body() {
		return body;
	}
	public VersionState state() {
		return state;
	}
}
