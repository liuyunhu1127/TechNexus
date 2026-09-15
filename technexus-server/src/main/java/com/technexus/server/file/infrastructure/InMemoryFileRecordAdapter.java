package com.technexus.server.file.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.FileRecordPort;
import java.util.Map;
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

	@Override
	public void begin(UploadRecord upload) {
		uploads.put(upload.uploadId(), upload);
		files.put(upload.fileId(), new StoredFile(upload, "UPLOADING", null, 0));
	}

	@Override
	public UploadRecord requireUpload(UUID uploadId, UUID ownerId) {
		var upload = uploads.get(uploadId);
		if (upload == null || !upload.ownerId().equals(ownerId))
			throw new DomainException("RESOURCE_NOT_FOUND", "上传会话不存在");
		return upload;
	}

	@Override
	public void markScanning(UUID uploadId, UUID ownerId) {
		var upload = requireUpload(uploadId, ownerId);
		files.computeIfPresent(upload.fileId(),
				(ignored, value) -> new StoredFile(upload, "SCANNING", null, value.attempt()));
	}

	@Override
	public Optional<ScanCandidate> claimNextScan(Instant now) {
		return files.values().stream().filter(value -> "SCANNING".equals(value.state())).findFirst().map(value -> {
			files.put(value.upload().fileId(),
					new StoredFile(value.upload(), value.state(), value.detectedMime(), value.attempt() + 1));
			return new ScanCandidate(value.upload().fileId(), value.upload().objectKey(), value.upload().declaredMime(),
					value.attempt() + 1);
		});
	}
	@Override
	public void markAvailable(UUID fileId, String detectedMime) {
		files.computeIfPresent(fileId,
				(ignored, value) -> new StoredFile(value.upload(), "AVAILABLE", detectedMime, value.attempt()));
	}
	@Override
	public void markBlocked(UUID fileId, String reason) {
		files.computeIfPresent(fileId,
				(ignored, value) -> new StoredFile(value.upload(), "BLOCKED", value.detectedMime(), value.attempt()));
	}
	@Override
	public void markScanFailed(UUID fileId, int nextAttempt, Instant nextAttemptAt, String error) {
	}

	@Override
	public FileRecord requireDownloadable(UUID fileId, UUID actorId) {
		var file = files.get(fileId);
		if (file == null || !file.upload().ownerId().equals(actorId) || !"AVAILABLE".equals(file.state())) {
			throw new DomainException("FILE_UNAVAILABLE", "文件尚不可下载");
		}
		return new FileRecord(fileId, file.upload().objectKey(), file.detectedMime());
	}

	private record StoredFile(UploadRecord upload, String state, String detectedMime, int attempt) {
	}
}
