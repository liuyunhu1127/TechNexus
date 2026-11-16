package com.technexus.server.file.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FileRecordPort {
	UploadRecord begin(UploadRecord upload);
	UploadRecord requireUpload(UUID uploadId, UUID ownerId);
	String markScanning(UUID uploadId, UUID ownerId);
	Optional<ScanCandidate> claimNextScan(Instant now);
	Optional<ExpiredUpload> claimExpiredUpload(Instant now);
	void markUploadExpired(UUID uploadId, UUID fileId, boolean reused);
	void releaseExpiredUpload(UUID uploadId, String previousState);
	void markAvailable(UUID fileId, String detectedMime);
	void markBlocked(UUID fileId, String reason, String objectKey);
	void markScanFailed(UUID fileId, int nextAttempt, Instant nextAttemptAt, String error);
	FileStatus requireOwned(UUID fileId, UUID actorId);
	FileRecord requireDownloadable(UUID fileId, UUID actorId);

	record UploadRecord(UUID uploadId, UUID fileId, UUID ownerId, String objectKey, String sha256, long sizeBytes,
			String declaredMime, Instant expiresAt, String sessionState, String fileState) {
		public UploadRecord(UUID uploadId, UUID fileId, UUID ownerId, String objectKey, String sha256, long sizeBytes,
				String declaredMime, Instant expiresAt) {
			this(uploadId, fileId, ownerId, objectKey, sha256, sizeBytes, declaredMime, expiresAt, "CREATED",
					"UPLOADING");
		}

		public boolean reused() {
			return "REUSED".equals(sessionState);
		}
	}
	record FileRecord(UUID fileId, String objectKey, String detectedMime) {
	}
	record FileStatus(UUID fileId, String state, String detectedMime) {
	}
	record ScanCandidate(UUID fileId, String objectKey, String declaredMime, String sha256, int attempt) {
	}
	record ExpiredUpload(UUID uploadId, UUID fileId, String objectKey, String previousState) {
		public boolean reused() {
			return "REUSED".equals(previousState);
		}
	}
}
