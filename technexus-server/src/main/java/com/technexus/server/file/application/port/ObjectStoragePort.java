package com.technexus.server.file.application.port;

import java.net.URI;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;

public interface ObjectStoragePort {
	PresignedUpload presignUpload(String objectKey, String declaredMime, String sha256Hex);
	ObjectMetadata inspect(String objectKey);
	InputStream openContent(String objectKey);
	PresignedDownload presignDownload(String objectKey);

	record PresignedUpload(URI url, Map<String, String> requiredHeaders, Instant expiresAt) {
	}
	record PresignedDownload(URI url, Instant expiresAt) {
	}
	record ObjectMetadata(long sizeBytes, String contentType, String sha256) {
	}
}
