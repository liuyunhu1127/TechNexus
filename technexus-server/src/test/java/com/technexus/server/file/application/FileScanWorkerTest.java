package com.technexus.server.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.MalwareScannerPort;
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

class FileScanWorkerTest {
	private static final String HASH = "8b3369944dd2a3fab39e32d1aeb1f763946a458ae3e6368a46432adc8f3a0860";
	private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

	@Test
	void cleanFileBecomesAvailableToItsOwner() {
		var fixture = fixture(content -> {
			consume(content);
			return MalwareScannerPort.ScanResult.passed();
		});
		assertTrue(fixture.worker().scanNext());
		assertEquals("text/plain",
				fixture.records().requireDownloadable(fixture.fileId(), fixture.ownerId()).detectedMime());
	}

	@Test
	void infectedFileIsBlockedAndNeverDownloadable() {
		var fixture = fixture(content -> {
			consume(content);
			return MalwareScannerPort.ScanResult.infected("Eicar-Test-Signature");
		});
		assertTrue(fixture.worker().scanNext());
		assertThrows(DomainException.class,
				() -> fixture.records().requireDownloadable(fixture.fileId(), fixture.ownerId()));
		assertEquals("quarantine/" + fixture.fileId(), fixture.storage().quarantineKey);
		assertFalse(fixture.worker().scanNext());
	}

	@Test
	void unavailableScannerRetriesThenFailsClosed() {
		var fixture = fixture(content -> {
			throw new DomainException("FILE_SCAN_UNAVAILABLE", "offline");
		});
		for (int attempt = 0; attempt < 5; attempt++)
			assertTrue(fixture.worker().scanNext());
		assertFalse(fixture.worker().scanNext());
		assertThrows(DomainException.class,
				() -> fixture.records().requireDownloadable(fixture.fileId(), fixture.ownerId()));
	}

	@Test
	void recomputesActualHashEvenWhenStorageMetadataClaimsExpectedHash() {
		var records = new InMemoryFileRecordAdapter();
		var storage = new FakeStorage();
		var claimedHash = "0".repeat(64);
		storage.metadataHash = claimedHash;
		var files = new FileApplicationService(records, storage);
		var ownerId = UUID.randomUUID();
		var upload = files.createUpload(ownerId,
				new FileApplicationService.UploadCommand("notes.txt", 4, "text/plain", claimedHash, "ATTACHMENT"));
		files.complete(ownerId, upload.uploadId());
		var worker = new FileScanWorker(records, storage, content -> {
			consume(content);
			return MalwareScannerPort.ScanResult.passed();
		}, Clock.fixed(NOW, ZoneOffset.UTC));

		assertTrue(worker.scanNext());
		assertEquals("quarantine/" + upload.fileId(), storage.quarantineKey);
		assertThrows(DomainException.class, () -> records.requireDownloadable(upload.fileId(), ownerId));
	}

	private static Fixture fixture(MalwareScannerPort scanner) {
		var records = new InMemoryFileRecordAdapter();
		var storage = new FakeStorage();
		var files = new FileApplicationService(records, storage);
		var ownerId = UUID.randomUUID();
		var upload = files.createUpload(ownerId,
				new FileApplicationService.UploadCommand("notes.txt", 4, "text/plain", HASH, "ATTACHMENT"));
		files.complete(ownerId, upload.uploadId());
		var worker = new FileScanWorker(records, storage, scanner, Clock.fixed(NOW, ZoneOffset.UTC));
		return new Fixture(records, storage, worker, ownerId, upload.fileId());
	}

	private static void consume(InputStream content) {
		try {
			content.readAllBytes();
		} catch (java.io.IOException error) {
			throw new IllegalStateException(error);
		}
	}

	private static final class FakeStorage implements ObjectStoragePort {
		private String quarantineKey;
		private String metadataHash = HASH;
		@Override
		public PresignedUpload presignUpload(String key, String mime, String sha256) {
			return new PresignedUpload(URI.create("https://storage.test/" + key), Map.of(), NOW.plusSeconds(900));
		}
		@Override
		public ObjectMetadata inspect(String key) {
			return new ObjectMetadata(4, "text/plain", metadataHash);
		}
		@Override
		public InputStream openContent(String key) {
			return new ByteArrayInputStream("safe".getBytes());
		}
		@Override
		public PresignedDownload presignDownload(String key) {
			return new PresignedDownload(URI.create("https://storage.test/" + key), NOW.plusSeconds(300));
		}
		@Override
		public void quarantine(String sourceKey, String quarantineKey) {
			this.quarantineKey = quarantineKey;
		}
	}

	private record Fixture(InMemoryFileRecordAdapter records, FakeStorage storage, FileScanWorker worker, UUID ownerId,
			UUID fileId) {
	}
}
