package com.technexus.server.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BearerTokenService {
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
	private final ObjectMapper objectMapper;
	private final byte[] secret;
	private final Clock clock;

	@Autowired
	public BearerTokenService(ObjectMapper objectMapper, @Value("${technexus.security.jwt-secret}") String secret) {
		this(objectMapper, secret, Clock.systemUTC());
	}

	BearerTokenService(ObjectMapper objectMapper, String secret, Clock clock) {
		this.objectMapper = objectMapper;
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("TECHNEXUS_JWT_SECRET must contain at least 32 UTF-8 bytes");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.clock = clock;
	}

	public String issue(UUID userId, UUID sid, long sessionVersion, boolean administrator) {
		try {
			var header = ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
			var claims = new LinkedHashMap<String, Object>();
			claims.put("sub", userId.toString());
			claims.put("sid", sid.toString());
			claims.put("session_version", sessionVersion);
			claims.put("scope", administrator ? "user admin operations" : "user");
			claims.put("iat", clock.instant().getEpochSecond());
			claims.put("exp", clock.instant().plusSeconds(900).getEpochSecond());
			var payload = ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
			var unsigned = header + "." + payload;
			return unsigned + "." + ENCODER.encodeToString(sign(unsigned));
		} catch (Exception error) {
			throw new IllegalStateException("Could not issue token", error);
		}
	}

	public Claims verify(String token) {
		try {
			var parts = token.split("\\.");
			if (parts.length != 3)
				throw new DomainException("TOKEN_INVALID", "令牌结构无效");
			var header = new String(DECODER.decode(parts[0]), StandardCharsets.UTF_8);
			if (!"{\"alg\":\"HS256\",\"typ\":\"JWT\"}".equals(header)) {
				throw new DomainException("TOKEN_INVALID", "令牌算法无效");
			}
			var unsigned = parts[0] + "." + parts[1];
			var suppliedSignature = DECODER.decode(parts[2]);
			if (!ENCODER.encodeToString(suppliedSignature).equals(parts[2])
					|| !MessageDigest.isEqual(sign(unsigned), suppliedSignature)) {
				throw new DomainException("TOKEN_INVALID", "令牌签名无效");
			}
			Map<String, Object> values = objectMapper.readValue(DECODER.decode(parts[1]), new TypeReference<>() {
			});
			var expiry = Instant.ofEpochSecond(((Number) values.get("exp")).longValue());
			if (!expiry.isAfter(clock.instant()))
				throw new DomainException("TOKEN_EXPIRED", "访问令牌已过期");
			return new Claims(UUID.fromString((String) values.get("sub")), UUID.fromString((String) values.get("sid")),
					((Number) values.get("session_version")).longValue(), String.valueOf(values.get("scope")), expiry);
		} catch (DomainException error) {
			throw error;
		} catch (Exception error) {
			throw new DomainException("TOKEN_INVALID", "访问令牌无效");
		}
	}

	private byte[] sign(String input) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret, "HmacSHA256"));
		return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
	}

	public record Claims(UUID userId, UUID sid, long sessionVersion, String scope, Instant expiresAt) {
	}
}
