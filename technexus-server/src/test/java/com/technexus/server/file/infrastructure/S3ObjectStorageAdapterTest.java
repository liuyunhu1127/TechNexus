package com.technexus.server.file.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
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

	@Test
	void usesPublicEndpointForBrowserSignaturesAndRestrictsInsecureInternalEndpoints() {
		var separated = new S3ObjectStorageAdapter(URI.create("https://files.example.com:9000"),
				URI.create("http://minio:9000"), "technexus-files", "us-east-1", "access-key",
				"a-secret-key-longer-than-sixteen-bytes",
				Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC), HttpClient.newHttpClient());

		assertEquals("files.example.com", separated.presignDownload("uploads/a.txt").url().getHost());
		assertThrows(IllegalStateException.class,
				() -> new S3ObjectStorageAdapter(URI.create("https://files.example.com"),
						URI.create("http://storage.example.net:9000"), "technexus-files", "us-east-1", "access-key",
						"a-secret-key-longer-than-sixteen-bytes", Clock.systemUTC(), HttpClient.newHttpClient()));
	}

	@Test
	void sendsServerSideObjectRequestsToTheInternalEndpoint() throws Exception {
		var requests = new ConcurrentLinkedQueue<String>();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/storage/technexus-files/", exchange -> {
			try (exchange) {
				var method = exchange.getRequestMethod();
				requests.add(method + " " + exchange.getRequestURI().getPath());
				if ("HEAD".equals(method)) {
					exchange.getResponseHeaders().set("Content-Length", "2");
					exchange.getResponseHeaders().set("Content-Type", "text/plain");
					exchange.sendResponseHeaders(200, -1);
				} else if ("GET".equals(method)) {
					exchange.sendResponseHeaders(200, 2);
					exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
				} else {
					exchange.sendResponseHeaders(204, -1);
				}
			}
		});
		server.start();
		try {
			var internal = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/storage");
			var separated = new S3ObjectStorageAdapter(URI.create("https://files.example.invalid"), internal,
					"technexus-files", "us-east-1", "access-key", "a-secret-key-longer-than-sixteen-bytes",
					Clock.systemUTC(), HttpClient.newHttpClient());
			assertEquals(2L, separated.inspect("uploads/a.txt").sizeBytes());
			try (var content = separated.openContent("uploads/a.txt")) {
				assertEquals("ok", new String(content.readAllBytes(), StandardCharsets.UTF_8));
			}
			separated.quarantine("uploads/a.txt", "quarantine/a.txt");
			assertEquals(List.of("HEAD /storage/technexus-files/uploads/a.txt",
					"GET /storage/technexus-files/uploads/a.txt", "PUT /storage/technexus-files/quarantine/a.txt",
					"DELETE /storage/technexus-files/uploads/a.txt"), List.copyOf(requests));
		} finally {
			server.stop(0);
		}
	}
}
