package com.technexus.server.file.infrastructure;

import com.technexus.server.file.application.port.ObjectStoragePort;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test")
public class TestObjectStorageAdapter implements ObjectStoragePort {
	@Override
	public PresignedUpload presignUpload(String key, String mime, String hash) {
		return new PresignedUpload(URI.create("https://storage.test/" + key), Map.of("Content-Type", mime),
				Instant.now().plusSeconds(900));
	}
	@Override
	public ObjectMetadata inspect(String key) {
		return new ObjectMetadata(1, "application/octet-stream", "");
	}
	@Override
	public InputStream openContent(String key) {
		return new ByteArrayInputStream(new byte[]{0});
	}
	@Override
	public PresignedDownload presignDownload(String key) {
		return new PresignedDownload(URI.create("https://storage.test/" + key), Instant.now().plusSeconds(300));
	}
}
