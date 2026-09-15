package com.technexus.content.domain;

import com.technexus.common.domain.DomainException;
import java.util.Objects;
import java.util.UUID;

public final class ArticleVersion {
	private final UUID publicId;
	private final int revision;
	private final String title;
	private final String summary;
	private final String body;
	private VersionState state = VersionState.DRAFT;

	public ArticleVersion(UUID publicId, int revision, String title, String summary, String body) {
		this.publicId = Objects.requireNonNull(publicId);
		if (revision < 1)
			throw new DomainException("REVISION_INVALID", "修订号必须从 1 开始");
		this.revision = revision;
		this.title = requireText(title, 160, "标题");
		this.summary = summary == null ? "" : requireText(summary, 500, "摘要");
		this.body = requireText(body, 200_000, "正文");
	}

	public static ArticleVersion restore(UUID publicId, int revision, String title, String summary, String body,
			VersionState state) {
		var version = new ArticleVersion(publicId, revision, title, summary, body);
		version.state = Objects.requireNonNull(state);
		return version;
	}

	void submit() {
		if (state != VersionState.DRAFT)
			throw new DomainException("VERSION_NOT_DRAFT", "只有草稿版本可以提交");
		state = VersionState.PENDING_REVIEW;
	}

	void approve() {
		if (state != VersionState.PENDING_REVIEW)
			throw new DomainException("VERSION_NOT_PENDING", "版本不在待审状态");
		state = VersionState.APPROVED;
	}

	void reject() {
		if (state != VersionState.PENDING_REVIEW)
			throw new DomainException("VERSION_NOT_PENDING", "版本不在待审状态");
		state = VersionState.REJECTED;
	}

	static String requireText(String value, int max, String field) {
		var result = Objects.requireNonNull(value).trim();
		if (result.isEmpty() || result.length() > max)
			throw new DomainException("CONTENT_FIELD_INVALID", field + "长度无效");
		return result;
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
	public String summary() {
		return summary;
	}
	public String body() {
		return body;
	}
	public VersionState state() {
		return state;
	}
}
