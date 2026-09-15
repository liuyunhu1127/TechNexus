package com.technexus.server.file.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.ObjectStoragePort;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class S3ObjectStorageAdapter implements ObjectStoragePort {
	private static final DateTimeFormatter AMZ_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
	private final URI endpoint;
	private final String bucket;
	private final String region;
	private final String accessKey;
	private final String secretKey;
	private final Clock clock;
	private final HttpClient http;

	@Autowired
	public S3ObjectStorageAdapter(@Value("${technexus.storage.endpoint}") URI endpoint,
			@Value("${technexus.storage.bucket}") String bucket,
			@Value("${technexus.storage.region:us-east-1}") String region,
			@Value("${technexus.storage.access-key}") String accessKey,
			@Value("${technexus.storage.secret-key}") String secretKey) {
		this(endpoint, bucket, region, accessKey, secretKey, Clock.systemUTC(),
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	S3ObjectStorageAdapter(URI endpoint, String bucket, String region, String accessKey, String secretKey, Clock clock,
			HttpClient http) {
		if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !isLocal(endpoint.getHost())) {
			throw new IllegalStateException("Object storage endpoint must use HTTPS outside localhost");
		}
		if (!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
			throw new IllegalStateException("Invalid object storage bucket");
		if (accessKey.isBlank() || secretKey.length() < 16)
			throw new IllegalStateException("Object storage credentials are missing");
		this.endpoint = endpoint;
		this.bucket = bucket;
		this.region = region;
		this.accessKey = accessKey;
		this.secretKey = secretKey;
		this.clock = clock;
		this.http = http;
	}

	@Override
	public PresignedUpload presignUpload(String key, String mime, String sha256Hex) {
		var checksum = Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha256Hex));
		var headers = Map.of("content-type", mime, "x-amz-checksum-sha256", checksum);
		return new PresignedUpload(presign("PUT", key, 900, headers),
				Map.of("Content-Type", mime, "x-amz-checksum-sha256", checksum), clock.instant().plusSeconds(900));
	}

	@Override
	public ObjectMetadata inspect(String key) {
		try {
			var uri = presign("HEAD", key, 60, Map.of("x-amz-checksum-mode", "ENABLED"));
			var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
					.header("x-amz-checksum-mode", "ENABLED").method("HEAD", HttpRequest.BodyPublishers.noBody())
					.build();
			var response = http.send(request, HttpResponse.BodyHandlers.discarding());
			if (response.statusCode() / 100 != 2)
				throw new DomainException("OBJECT_NOT_READY", "对象存储尚未确认上传对象");
			var length = response.headers().firstValueAsLong("content-length").orElseThrow();
			var mime = response.headers().firstValue("content-type").orElse("application/octet-stream").split(";",
					2)[0];
			var hash = response.headers().firstValue("x-amz-checksum-sha256")
					.or(() -> response.headers().firstValue("x-amz-meta-sha256")).orElse(null);
			return new ObjectMetadata(length, mime, hash);
		} catch (DomainException error) {
			throw error;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "对象存储暂不可用");
		} catch (Exception error) {
			throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "对象存储暂不可用");
		}
	}

	@Override
	public InputStream openContent(String key) {
		try {
			var request = HttpRequest.newBuilder(presign("GET", key, 60, Map.of())).timeout(Duration.ofSeconds(30))
					.GET().build();
			var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() / 100 != 2) {
				response.body().close();
				throw new DomainException("OBJECT_NOT_READY", "对象存储尚未提供上传对象");
			}
			return response.body();
		} catch (DomainException error) {
			throw error;
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "对象存储暂不可用");
		} catch (Exception error) {
			throw new DomainException("OBJECT_STORAGE_UNAVAILABLE", "对象存储暂不可用");
		}
	}

	@Override
	public PresignedDownload presignDownload(String key) {
		return new PresignedDownload(presign("GET", key, 300, Map.of()), clock.instant().plusSeconds(300));
	}

	private URI presign(String method, String key, int expires, Map<String, String> requiredHeaders) {
		try {
			var now = clock.instant();
			var day = DAY.format(now);
			var scope = day + "/" + region + "/s3/aws4_request";
			var canonicalUri = normalizedEndpointPath() + "/" + encode(bucket) + "/" + encodeKey(key);
			var headers = new TreeMap<String, String>();
			headers.put("host",
					endpoint.getPort() < 0 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort());
			requiredHeaders.forEach((name, value) -> headers.put(name.toLowerCase(), value.trim()));
			var signedHeaders = String.join(";", headers.keySet());
			var query = new TreeMap<String, String>();
			query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
			query.put("X-Amz-Credential", accessKey + "/" + scope);
			query.put("X-Amz-Date", AMZ_TIME.format(now));
			query.put("X-Amz-Expires", String.valueOf(expires));
			query.put("X-Amz-SignedHeaders", signedHeaders);
			var canonicalQuery = query.entrySet().stream()
					.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
					.collect(java.util.stream.Collectors.joining("&"));
			var canonicalHeaders = headers.entrySet().stream()
					.map(entry -> entry.getKey() + ":" + entry.getValue() + "\n")
					.collect(java.util.stream.Collectors.joining());
			var canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
					+ signedHeaders + "\nUNSIGNED-PAYLOAD";
			var stringToSign = "AWS4-HMAC-SHA256\n" + AMZ_TIME.format(now) + "\n" + scope + "\n"
					+ hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
			var signingKey = hmac(
					hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day), region), "s3"),
					"aws4_request");
			var signature = hex(hmac(signingKey, stringToSign));
			var authority = endpoint.getRawAuthority();
			return URI.create(endpoint.getScheme() + "://" + authority + canonicalUri + "?" + canonicalQuery
					+ "&X-Amz-Signature=" + signature);
		} catch (Exception error) {
			throw new IllegalStateException("Could not create object storage signature", error);
		}
	}

	private String normalizedEndpointPath() {
		var path = endpoint.getRawPath();
		if (path == null || path.isBlank() || "/".equals(path))
			return "";
		return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}
	private static boolean isLocal(String host) {
		return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
	}
	private static String encodeKey(String key) {
		return java.util.Arrays.stream(key.split("/", -1)).map(S3ObjectStorageAdapter::encode)
				.collect(java.util.stream.Collectors.joining("/"));
	}
	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~").replace("*",
				"%2A");
	}
	private static byte[] sha256(byte[] value) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(value);
	}
	private static byte[] hmac(byte[] key, String value) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
	}
	private static String hex(byte[] value) {
		return java.util.HexFormat.of().formatHex(value);
	}
}
