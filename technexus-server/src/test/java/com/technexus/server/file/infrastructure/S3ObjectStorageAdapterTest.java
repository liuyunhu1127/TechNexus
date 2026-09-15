package com.technexus.server.file.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class S3ObjectStorageAdapterTest {
	private final S3ObjectStorageAdapter storage = new S3ObjectStorageAdapter(URI.create("https://storage.example.com"),
			"technexus-files", "us-east-1", "access-key", "a-secret-key-longer-than-sixteen-bytes",
			Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC), HttpClient.newHttpClient());

	@Test
	void createsBoundUploadSignatureAndFiveMinuteDownloadSignature() {
		var upload = storage.presignUpload("uploads/user/file/a b.txt", "text/plain",
				"0000000000000000000000000000000000000000000000000000000000000000");
		var download = storage.presignDownload("uploads/user/file/a b.txt");

		assertTrue(upload.url().toString().contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
		assertTrue(upload.url().toString().contains("a%20b.txt"));
		assertEquals("text/plain", upload.requiredHeaders().get("Content-Type"));
		assertTrue(download.url().toString().contains("X-Amz-Expires=300"));
	}
}
