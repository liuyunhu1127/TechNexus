package com.technexus.file.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import java.util.Objects;
import java.util.UUID;

public final class FileObject extends AggregateRoot {
	private final UUID publicId;
	private final String sha256;
	private final long sizeBytes;
	private final String objectKey;
	private FileState state = FileState.UPLOADING;
	private String detectedMime;

	public FileObject(UUID publicId, String sha256, long sizeBytes, String objectKey) {
		this.publicId = Objects.requireNonNull(publicId);
		if (!Objects.requireNonNull(sha256).matches("[a-f0-9]{64}"))
			throw new DomainException("FILE_HASH_INVALID", "SHA-256 无效");
		if (sizeBytes <= 0 || sizeBytes > 100L * 1024 * 1024)
			throw new DomainException("FILE_SIZE_INVALID", "文件大小无效");
		this.sha256 = sha256;
		this.sizeBytes = sizeBytes;
		this.objectKey = Objects.requireNonNull(objectKey);
	}

	public void uploaded() {
		requireState(FileState.UPLOADING);
		state = FileState.UPLOADED;
		changed();
	}
	public void scanning() {
		requireState(FileState.UPLOADED);
		state = FileState.SCANNING;
		changed();
	}

	public void scanPassed(String mime) {
		requireState(FileState.SCANNING);
		detectedMime = Objects.requireNonNull(mime);
		state = FileState.AVAILABLE;
		changed();
	}

	public void block() {
		state = FileState.BLOCKED;
		changed();
	}

	public void requireDownloadable() {
		if (state != FileState.AVAILABLE)
			throw new DomainException("FILE_UNAVAILABLE", "文件尚不可下载");
	}

	private void requireState(FileState expected) {
		if (state != expected)
			throw new DomainException("FILE_TRANSITION_INVALID", "文件状态转换无效");
	}

	public UUID publicId() {
		return publicId;
	}
	public String sha256() {
		return sha256;
	}
	public long sizeBytes() {
		return sizeBytes;
	}
	public String objectKey() {
		return objectKey;
	}
	public FileState state() {
		return state;
	}
	public String detectedMime() {
		return detectedMime;
	}
}
