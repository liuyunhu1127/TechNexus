package com.technexus.server.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.server.file.application.port.ObjectStoragePort;
import com.technexus.server.file.infrastructure.InMemoryFileRecordAdapter;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileApplicationServiceTest {
	@Test
	void validatesObjectMetadataBeforeMovingUploadToScanning() {
		var hash = "0000000000000000000000000000000000000000000000000000000000000000";
		var storage = new ObjectStoragePort() {
			@Override
			public PresignedUpload presignUpload(String key, String mime, String sha) {
				return new PresignedUpload(URI.create("https://storage.test/" + key), Map.of("Content-Type", mime),
						Instant.now().plusSeconds(900));
			}
			@Override
			public ObjectMetadata inspect(String key) {
				return new ObjectMetadata(42, "text/plain", hash);
			}
			@Override
			public InputStream openContent(String key) {
				return new ByteArrayInputStream("safe".getBytes());
			}
			@Override
			public PresignedDownload presignDownload(String key) {
				return new PresignedDownload(URI.create("https://storage.test/" + key), Instant.now().plusSeconds(300));
			}
		};
		var files = new FileApplicationService(new InMemoryFileRecordAdapter(), storage);
		var ownerId = UUID.randomUUID();

		var upload = files.createUpload(ownerId,
				new FileApplicationService.UploadCommand("notes.txt", 42, "text/plain", hash, "ATTACHMENT"));
		var completed = files.complete(ownerId, upload.uploadId());

		assertEquals("SCANNING", completed.state());
		assertTrue(upload.uploadUrl().startsWith("https://storage.test/uploads/"));
	}
}
