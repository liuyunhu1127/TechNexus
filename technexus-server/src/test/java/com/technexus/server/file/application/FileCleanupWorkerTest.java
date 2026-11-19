package com.technexus.server.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.ObjectStoragePort;
import com.technexus.server.file.infrastructure.InMemoryFileRecordAdapter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileCleanupWorkerTest {
	private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

	@Test
	void deletesExpiredUnfinishedUploadAndMarksItExpired() {
		var records = new InMemoryFileRecordAdapter();
		var uploadId = UUID.randomUUID();
		var fileId = UUID.randomUUID();
		var key = "uploads/owner/" + fileId + "/expired.txt";
		records.begin(new FileRecordPort.UploadRecord(uploadId, fileId, UUID.randomUUID(), key, "0".repeat(64), 4,
				"text/plain", NOW.minusSeconds(1)));
		var storage = new FakeStorage();
		var worker = new FileCleanupWorker(records, storage, Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(worker.cleanNext());
		assertEquals(key, storage.deletedKey);
		assertFalse(worker.cleanNext());
	}

	private static final class FakeStorage implements ObjectStoragePort {
		private String deletedKey;
		@Override
		public PresignedUpload presignUpload(String key, String mime, String hash) {
			return new PresignedUpload(URI.create("https://storage.test/" + key), Map.of(), NOW.plusSeconds(60));
		}
		@Override
		public ObjectMetadata inspect(String key) {
			return new ObjectMetadata(4, "text/plain", "0".repeat(64));
		}
		@Override
		public InputStream openContent(String key) {
			return new ByteArrayInputStream(new byte[0]);
		}
		@Override
		public PresignedDownload presignDownload(String key) {
			return new PresignedDownload(URI.create("https://storage.test/" + key), NOW.plusSeconds(60));
		}
		@Override
		public void quarantine(String sourceKey, String quarantineKey) {
		}
		@Override
		public void delete(String key) {
			deletedKey = key;
		}
	}
}
