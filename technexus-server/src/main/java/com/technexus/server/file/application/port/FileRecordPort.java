package com.technexus.server.file.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FileRecordPort {
	void begin(UploadRecord upload);
	UploadRecord requireUpload(UUID uploadId, UUID ownerId);
	void markScanning(UUID uploadId, UUID ownerId);
	Optional<ScanCandidate> claimNextScan(Instant now);
	void markAvailable(UUID fileId, String detectedMime);
	void markBlocked(UUID fileId, String reason);
	void markScanFailed(UUID fileId, int nextAttempt, Instant nextAttemptAt, String error);
	FileRecord requireDownloadable(UUID fileId, UUID actorId);

	record UploadRecord(UUID uploadId, UUID fileId, UUID ownerId, String objectKey, String sha256, long sizeBytes,
			String declaredMime, Instant expiresAt) {
	}
	record FileRecord(UUID fileId, String objectKey, String detectedMime) {
	}
	record ScanCandidate(UUID fileId, String objectKey, String declaredMime, int attempt) {
	}
}
