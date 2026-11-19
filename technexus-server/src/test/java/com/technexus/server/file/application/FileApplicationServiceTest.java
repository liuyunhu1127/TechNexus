package com.technexus.server.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.ObjectStoragePort;
import com.technexus.server.file.infrastructure.InMemoryFileRecordAdapter;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
			@Override
			public void quarantine(String sourceKey, String quarantineKey) {
			}
		};
		var files = new FileApplicationService(new InMemoryFileRecordAdapter(), storage);
		var ownerId = UUID.randomUUID();

		var upload = files.createUpload(ownerId,
				new FileApplicationService.UploadCommand("notes.txt", 42, "text/plain", hash, "ATTACHMENT"));
		var completed = files.complete(ownerId, upload.uploadId());

		assertEquals("SCANNING", completed.state());
		assertEquals("SCANNING", files.get(ownerId, upload.fileId()).state());
		assertTrue(upload.uploadUrl().startsWith("https://storage.test/uploads/"));
	}

	@Test
	void requiresProofOfPossessionAndCreatesIndependentRelationsForDuplicateContent() {
		var hash = "1".repeat(64);
		var uploadCount = new AtomicInteger();
		var deleteCount = new AtomicInteger();
		var storage = new ObjectStoragePort() {
			@Override
			public PresignedUpload presignUpload(String key, String mime, String sha) {
				uploadCount.incrementAndGet();
				return new PresignedUpload(URI.create("https://storage.test/" + key), Map.of(),
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
			@Override
			public void quarantine(String sourceKey, String quarantineKey) {
			}
			@Override
			public void delete(String key) {
				assertTrue(key.startsWith("proofs/"));
				deleteCount.incrementAndGet();
			}
		};
		var records = new InMemoryFileRecordAdapter();
		var files = new FileApplicationService(records, storage);
		var firstOwner = UUID.randomUUID();
		var secondOwner = UUID.randomUUID();
		var unrelatedUser = UUID.randomUUID();
		var command = new FileApplicationService.UploadCommand("same.txt", 42, "text/plain", hash, "ATTACHMENT");

		var first = files.createUpload(firstOwner, command);
		files.complete(firstOwner, first.uploadId());
		var unavailable = assertThrows(DomainException.class, () -> files.createUpload(secondOwner, command));
		assertEquals("FILE_REUSE_UNAVAILABLE", unavailable.code());
		records.markAvailable(first.fileId(), "text/plain");
		assertEquals("AVAILABLE", files.get(firstOwner, first.fileId()).state());
		assertThrows(DomainException.class, () -> files.createDownload(unrelatedUser, first.fileId()));

		var duplicate = files.createUpload(secondOwner, command);
		assertEquals(first.fileId(), duplicate.fileId());
		assertTrue(duplicate.uploadUrl().contains("/proofs/"));
		assertThrows(DomainException.class, () -> files.createDownload(secondOwner, duplicate.fileId()));
		var completed = files.complete(secondOwner, duplicate.uploadId());

		assertEquals("AVAILABLE", completed.state());
		assertEquals(2, uploadCount.get());
		assertEquals(1, deleteCount.get());
		assertTrue(files.createDownload(firstOwner, first.fileId()).url().contains("https://storage.test/"));
		assertTrue(files.createDownload(secondOwner, duplicate.fileId()).url().contains("https://storage.test/"));
		assertThrows(DomainException.class, () -> files.createDownload(unrelatedUser, first.fileId()));
	}
}
