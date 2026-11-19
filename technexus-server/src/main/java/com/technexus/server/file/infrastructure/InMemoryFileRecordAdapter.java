package com.technexus.server.file.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.FileRecordPort;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryFileRecordAdapter implements FileRecordPort {
	private final Map<UUID, UploadRecord> uploads = new ConcurrentHashMap<>();
	private final Map<UUID, StoredFile> files = new ConcurrentHashMap<>();
	private final Map<String, UUID> digests = new ConcurrentHashMap<>();
	private final Map<UUID, Set<UUID>> relations = new ConcurrentHashMap<>();

	@Override
	public synchronized UploadRecord begin(UploadRecord upload) {
		var digest = upload.sha256() + ':' + upload.sizeBytes();
		var existingId = digests.get(digest);
		if (existingId == null) {
			digests.put(digest, upload.fileId());
			files.put(upload.fileId(), new StoredFile(upload, "UPLOADING", null, 0));
			uploads.put(upload.uploadId(), upload);
			return upload;
		}
		var existing = files.get(existingId);
		if (!"AVAILABLE".equals(existing.state()))
			throw new DomainException("FILE_REUSE_UNAVAILABLE", "相同内容的文件尚不可安全复用");
		var proofKey = "proofs/" + upload.ownerId() + "/" + upload.uploadId();
		var reused = new UploadRecord(upload.uploadId(), existingId, upload.ownerId(), proofKey, upload.sha256(),
				upload.sizeBytes(), upload.declaredMime(), upload.expiresAt(), "REUSED", existing.state());
		uploads.put(upload.uploadId(), reused);
		return reused;
	}

	@Override
	public UploadRecord requireUpload(UUID uploadId, UUID ownerId) {
		var upload = uploads.get(uploadId);
		if (upload == null || !upload.ownerId().equals(ownerId))
			throw new DomainException("RESOURCE_NOT_FOUND", "上传会话不存在");
		return upload;
	}

	@Override
	public String markScanning(UUID uploadId, UUID ownerId) {
		var upload = requireUpload(uploadId, ownerId);
		if (!upload.reused()) {
			files.computeIfPresent(upload.fileId(),
					(ignored, value) -> new StoredFile(upload, "SCANNING", null, value.attempt()));
		}
		relations.computeIfAbsent(upload.fileId(), ignored -> ConcurrentHashMap.newKeySet()).add(ownerId);
		return files.get(upload.fileId()).state();
	}

	@Override
	public Optional<ScanCandidate> claimNextScan(Instant now) {
		return files.values().stream().filter(value -> "SCANNING".equals(value.state())).findFirst().map(value -> {
			files.put(value.upload().fileId(),
					new StoredFile(value.upload(), value.state(), value.detectedMime(), value.attempt() + 1));
			return new ScanCandidate(value.upload().fileId(), value.upload().objectKey(), value.upload().declaredMime(),
					value.upload().sha256(), value.attempt() + 1);
		});
	}
	@Override
	public synchronized Optional<ExpiredUpload> claimExpiredUpload(Instant now) {
		return uploads.values().stream().filter(value -> value.expiresAt().compareTo(now) <= 0)
				.filter(value -> "CREATED".equals(value.sessionState()) || "REUSED".equals(value.sessionState()))
				.findFirst().map(value -> {
					var cleaning = withSessionState(value, "CLEANING");
					uploads.put(value.uploadId(), cleaning);
					return new ExpiredUpload(value.uploadId(), value.fileId(), value.objectKey(), value.sessionState());
				});
	}
	@Override
	public synchronized void markUploadExpired(UUID uploadId, UUID fileId, boolean reused) {
		var upload = uploads.get(uploadId);
		if (upload == null || !"CLEANING".equals(upload.sessionState()))
			throw new DomainException("FILE_CLEANUP_CONFLICT", "上传清理状态已变化");
		uploads.put(uploadId, withSessionState(upload, "EXPIRED"));
		if (!reused)
			files.computeIfPresent(fileId,
					(ignored, value) -> "UPLOADING".equals(value.state())
							? new StoredFile(value.upload(), "EXPIRED", value.detectedMime(), value.attempt())
							: value);
	}
	@Override
	public synchronized void releaseExpiredUpload(UUID uploadId, String previousState) {
		var upload = uploads.get(uploadId);
		if (upload != null && "CLEANING".equals(upload.sessionState()))
			uploads.put(uploadId, withSessionState(upload, previousState));
	}
	@Override
	public void markAvailable(UUID fileId, String detectedMime) {
		files.computeIfPresent(fileId,
				(ignored, value) -> new StoredFile(value.upload(), "AVAILABLE", detectedMime, value.attempt()));
	}
	@Override
	public void markBlocked(UUID fileId, String reason, String objectKey) {
		files.computeIfPresent(fileId, (ignored, value) -> new StoredFile(withObjectKey(value.upload(), objectKey),
				"BLOCKED", value.detectedMime(), value.attempt()));
	}
	@Override
	public void markScanFailed(UUID fileId, int nextAttempt, Instant nextAttemptAt, String error) {
	}

	@Override
	public FileStatus requireOwned(UUID fileId, UUID actorId) {
		var file = files.get(fileId);
		if (file == null || !relations.getOrDefault(fileId, Set.of()).contains(actorId))
			throw new DomainException("RESOURCE_NOT_FOUND", "文件不存在");
		return new FileStatus(fileId, file.state(), file.detectedMime());
	}

	@Override
	public FileRecord requireDownloadable(UUID fileId, UUID actorId) {
		var file = files.get(fileId);
		if (file == null || !relations.getOrDefault(fileId, Set.of()).contains(actorId)
				|| !"AVAILABLE".equals(file.state())) {
			throw new DomainException("FILE_UNAVAILABLE", "文件尚不可下载");
		}
		return new FileRecord(fileId, file.upload().objectKey(), file.detectedMime());
	}

	private record StoredFile(UploadRecord upload, String state, String detectedMime, int attempt) {
	}

	private static UploadRecord withObjectKey(UploadRecord value, String objectKey) {
		return new UploadRecord(value.uploadId(), value.fileId(), value.ownerId(), objectKey, value.sha256(),
				value.sizeBytes(), value.declaredMime(), value.expiresAt(), value.sessionState(), value.fileState());
	}

	private static UploadRecord withSessionState(UploadRecord value, String state) {
		return new UploadRecord(value.uploadId(), value.fileId(), value.ownerId(), value.objectKey(), value.sha256(),
				value.sizeBytes(), value.declaredMime(), value.expiresAt(), state, value.fileState());
	}
}
