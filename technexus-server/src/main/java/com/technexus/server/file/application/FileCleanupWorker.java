package com.technexus.server.file.application;

import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.ObjectStoragePort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "technexus.file", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupWorker {
	private final FileRecordPort records;
	private final ObjectStoragePort storage;
	private final Clock clock;

	@Autowired
	public FileCleanupWorker(FileRecordPort records, ObjectStoragePort storage) {
		this(records, storage, Clock.systemUTC());
	}

	FileCleanupWorker(FileRecordPort records, ObjectStoragePort storage, Clock clock) {
		this.records = records;
		this.storage = storage;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${technexus.file.cleanup.poll-ms:60000}")
	public void poll() {
		for (var count = 0; count < 20 && cleanNext(); count++) {
			// Bound each poll so cleanup cannot monopolize the scheduler.
		}
	}

	public boolean cleanNext() {
		var candidate = records.claimExpiredUpload(clock.instant());
		if (candidate.isEmpty())
			return false;
		var upload = candidate.get();
		try {
			storage.delete(upload.objectKey());
			records.markUploadExpired(upload.uploadId(), upload.fileId(), upload.reused());
		} catch (RuntimeException error) {
			records.releaseExpiredUpload(upload.uploadId(), upload.previousState());
			throw error;
		}
		return true;
	}
}
