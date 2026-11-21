package com.technexus.server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.FileRecordPort.UploadRecord;
import com.technexus.server.file.application.port.ObjectStoragePort;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"technexus.account-mail.enabled=true", "technexus.account-mail.from=notifications@example.test",
		"technexus.account-mail.web-origin=http://localhost:3000", "technexus.file.workers-enabled=false",
		"technexus.security.bootstrap-admin-email=account-admin@example.test", "spring.mail.host=127.0.0.1",
		"spring.mail.properties.mail.smtp.auth=false", "spring.mail.properties.mail.smtp.starttls.enable=false",
		"spring.mail.properties.mail.smtp.starttls.required=false"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
class AccountCenterHttpIntegrationTest {
	private static final String OLD_EMAIL = "original@example.test";
	private static final String NEW_EMAIL = "replacement@example.test";
	private static final String ADMIN = "account-admin@example.test";
	private static final String OLD_PASSWORD = "Local-Test-Original!";
	private static final String NEW_PASSWORD = "Local-Test-Replacement!";
	private static final Pattern EMAIL_CODE = Pattern.compile("验证码：([A-Za-z0-9_-]{24})");
	private static final Pattern RESET_LINK = Pattern
			.compile("http://localhost:3000/reset-password#token=([a-f0-9-]{36}\\.[A-Za-z0-9_-]{24})");
	private static final SmtpInbox INBOX = SmtpInbox.start();

	@Autowired
	private TestRestTemplate http;
	@Autowired
	private FileRecordPort files;
	@MockitoBean
	private ObjectStoragePort storage;

	@DynamicPropertySource
	static void smtp(DynamicPropertyRegistry properties) {
		properties.add("spring.mail.port", () -> INBOX.server.getLocalPort());
	}

	@AfterAll
	static void closeInbox() throws IOException {
		INBOX.server.close();
	}

	@BeforeEach
	void useHttpClientThatExposesUnauthorizedResponses() {
		http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
	}

	@Test
	void verifiesLegacyEmailChangesBothAddressesAndResetsWithARealLocalSmtpMessage() throws Exception {
		create(OLD_EMAIL);
		var originalSession = token(OLD_EMAIL, OLD_PASSWORD);
		var otherSession = token(OLD_EMAIL, OLD_PASSWORD);
		assertEquals(false, data(get("/me/security", originalSession)).get("verified"));
		assertEquals(HttpStatus.BAD_REQUEST,
				post("/me/email-change", originalSession, Map.of("email", NEW_EMAIL, "password", OLD_PASSWORD))
						.getStatusCode());
		var generic = post("/auth/forgot-password", null, Map.of("email", OLD_EMAIL));
		assertEquals(HttpStatus.ACCEPTED, generic.getStatusCode());
		assertEquals(generic.getBody(),
				post("/auth/forgot-password", null, Map.of("email", "unknown@example.test")).getBody());
		INBOX.assertEmpty();

		var verification = post("/me/email-verification", originalSession, Map.of("password", OLD_PASSWORD));
		assertEquals(HttpStatus.ACCEPTED, verification.getStatusCode());
		var code = INBOX.extract(OLD_EMAIL, EMAIL_CODE);
		var verificationInput = Map.of("requestId", data(verification).get("requestId"), "code", code);
		assertEquals(HttpStatus.NO_CONTENT,
				post("/me/email-verification/confirm", originalSession, verificationInput).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST,
				post("/me/email-verification/confirm", originalSession, verificationInput).getStatusCode());
		assertEquals(true, data(get("/me/security", originalSession)).get("verified"));

		assertEquals(HttpStatus.CONFLICT,
				post("/me/email-change", originalSession, Map.of("email", ADMIN, "password", OLD_PASSWORD))
						.getStatusCode());
		var change = post("/me/email-change", originalSession, Map.of("email", NEW_EMAIL, "password", OLD_PASSWORD));
		assertEquals(HttpStatus.ACCEPTED, change.getStatusCode());
		var oldCode = INBOX.extract(OLD_EMAIL, EMAIL_CODE);
		var newCode = INBOX.extract(NEW_EMAIL, EMAIL_CODE);
		var requestId = data(change).get("requestId");
		assertEquals(HttpStatus.BAD_REQUEST, post("/me/email-change/confirm", originalSession,
				Map.of("requestId", requestId, "oldCode", oldCode, "newCode", oldCode)).getStatusCode());
		assertEquals(OLD_EMAIL, data(get("/me/security", originalSession)).get("email"));
		assertEquals(HttpStatus.NO_CONTENT, post("/me/email-change/confirm", originalSession,
				Map.of("requestId", requestId, "oldCode", oldCode, "newCode", newCode)).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, get("/me", originalSession).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, get("/me", otherSession).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, login(OLD_EMAIL, OLD_PASSWORD).getStatusCode());
		var changedSession = login(NEW_EMAIL, OLD_PASSWORD);
		assertEquals(HttpStatus.OK, changedSession.getStatusCode());
		assertEquals(generic.getBody(), post("/auth/forgot-password", null, Map.of("email", NEW_EMAIL)).getBody());
		var reset = INBOX.extract(NEW_EMAIL, RESET_LINK);
		var resetInput = Map.of("token", reset, "password", NEW_PASSWORD);
		assertEquals(HttpStatus.NO_CONTENT, post("/auth/reset-password", null, resetInput).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST, post("/auth/reset-password", null, resetInput).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED,
				get("/me", (String) changedSession.getBody().get("accessToken")).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, refresh(changedSession).getStatusCode());
		assertEquals(HttpStatus.UNAUTHORIZED, login(NEW_EMAIL, OLD_PASSWORD).getStatusCode());
		assertEquals(HttpStatus.OK, login(NEW_EMAIL, NEW_PASSWORD).getStatusCode());
		assertTrue(INBOX.take(NEW_EMAIL).contains("密码已重置"));
	}

	@Test
	void bootstrapAdminCanUseOnlyAnOwnedScannedAvatarAndReceivesNoRecoveryEmail() throws Exception {
		create(ADMIN);
		var adminToken = token(ADMIN, OLD_PASSWORD);
		var otherEmail = "other-avatar@example.test";
		var otherId = create(otherEmail);
		var otherToken = token(otherEmail, OLD_PASSWORD);
		assertEquals(true, data(get("/me/security", adminToken)).get("bootstrapAdmin"));
		assertEquals(HttpStatus.FORBIDDEN,
				post("/me/email-verification", adminToken, Map.of("password", OLD_PASSWORD)).getStatusCode());
		assertEquals(HttpStatus.FORBIDDEN,
				post("/me/email-change", adminToken, Map.of("email", "unused@example.test", "password", OLD_PASSWORD))
						.getStatusCode());
		assertEquals(HttpStatus.ACCEPTED, post("/auth/forgot-password", null, Map.of("email", ADMIN)).getStatusCode());
		INBOX.assertEmpty();

		var ownerId = UUID.fromString((String) data(get("/me", adminToken)).get("id"));
		var fileId = prepareAvatar(ownerId);
		var foreign = prepareAvatar(otherId);
		files.markAvailable(foreign, "image/png");
		var version = data(get("/me/avatar", adminToken)).get("version");
		assertEquals(HttpStatus.NOT_FOUND, exchange(HttpMethod.PUT, "/me/avatar", adminToken,
				Map.of("fileId", foreign, "expectedVersion", version)).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST,
				exchange(HttpMethod.PUT, "/me/avatar", adminToken, Map.of("fileId", fileId, "expectedVersion", version))
						.getStatusCode());
		files.markAvailable(fileId, "image/png");
		var png = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB), "png", png);
		when(storage.openContent("original/" + fileId))
				.thenAnswer(invocation -> new ByteArrayInputStream(png.toByteArray()));
		when(storage.presignDownload(anyString())).thenAnswer(invocation -> new ObjectStoragePort.PresignedDownload(
				URI.create("https://storage.invalid/" + invocation.getArgument(0)), Instant.now().plusSeconds(300)));
		var saved = exchange(HttpMethod.PUT, "/me/avatar", adminToken,
				Map.of("fileId", fileId, "expectedVersion", version));
		assertEquals(HttpStatus.OK, saved.getStatusCode());
		var sanitized = ArgumentCaptor.forClass(byte[].class);
		var key = ArgumentCaptor.forClass(String.class);
		verify(storage).storeAvatar(key.capture(), sanitized.capture());
		var image = ImageIO.read(new ByteArrayInputStream(sanitized.getValue()));
		assertArrayEquals(new int[]{512, 512}, new int[]{image.getWidth(), image.getHeight()});
		var avatar = get("/me/avatar", adminToken);
		assertEquals("no-store", avatar.getHeaders().getCacheControl());
		assertTrue(((String) data(avatar).get("url")).endsWith(key.getValue()));
		assertNull(data(get("/me/avatar", otherToken)).get("url"));
		assertEquals(HttpStatus.CONFLICT,
				exchange(HttpMethod.PUT, "/me/avatar", adminToken, Map.of("expectedVersion", version)).getStatusCode());
		assertEquals(HttpStatus.OK, exchange(HttpMethod.PUT, "/me/avatar", adminToken,
				Map.of("expectedVersion", data(saved).get("version"))).getStatusCode());
		verify(storage).delete(key.getValue());
		assertNull(data(get("/me/avatar", adminToken)).get("url"));
	}

	private UUID prepareAvatar(UUID owner) {
		var id = UUID.randomUUID();
		var upload = UUID.randomUUID();
		files.begin(new UploadRecord(upload, id, owner, "original/" + id, id.toString(), 123, "image/png",
				Instant.now().plusSeconds(300)));
		files.markScanning(upload, owner);
		return id;
	}

	private UUID create(String email) {
		assertEquals(HttpStatus.CREATED, post("/auth/register", null,
				Map.of("email", email, "password", OLD_PASSWORD, "displayName", "Local Test")).getStatusCode());
		return UUID.fromString((String) data(get("/me", token(email, OLD_PASSWORD))).get("id"));
	}

	private String token(String email, String password) {
		var response = login(email, password);
		assertEquals(HttpStatus.OK, response.getStatusCode());
		return (String) response.getBody().get("accessToken");
	}

	private ResponseEntity<Map> login(String email, String password) {
		return post("/auth/login", null, Map.of("account", email, "password", password));
	}

	private ResponseEntity<Map> refresh(ResponseEntity<Map> session) {
		var headers = new HttpHeaders();
		headers.setOrigin("http://localhost:3000");
		var cookies = session.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE);
		headers.add(HttpHeaders.COOKIE,
				String.join("; ", cookies.stream().map(value -> value.split(";", 2)[0]).toList()));
		headers.set("X-CSRF-Token", (String) session.getBody().get("csrfToken"));
		return http.exchange("/api/v1/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), Map.class);
	}

	private ResponseEntity<Map> get(String path, String token) {
		return exchange(HttpMethod.GET, path, token, null);
	}

	private ResponseEntity<Map> post(String path, String token, Map<String, ?> body) {
		return exchange(HttpMethod.POST, path, token, body);
	}

	private ResponseEntity<Map> exchange(HttpMethod method, String path, String token, Map<String, ?> body) {
		var headers = new HttpHeaders();
		if (token != null)
			headers.setBearerAuth(token);
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("Idempotency-Key", UUID.randomUUID().toString());
		}
		return http.exchange("/api/v1" + path, method, new HttpEntity<>(body, headers), Map.class);
	}

	private static Map<?, ?> data(ResponseEntity<Map> response) {
		return (Map<?, ?>) response.getBody().get("data");
	}

	private static final class SmtpInbox {
		private final ServerSocket server;
		private final LinkedBlockingQueue<MimeMessage> messages = new LinkedBlockingQueue<>();

		private SmtpInbox(ServerSocket server) {
			this.server = server;
		}

		static SmtpInbox start() {
			try {
				var inbox = new SmtpInbox(new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")));
				Thread.ofVirtual().start(inbox::accept);
				return inbox;
			} catch (IOException failure) {
				throw new IllegalStateException("Cannot start local SMTP inbox", failure);
			}
		}

		private void accept() {
			while (!server.isClosed()) {
				try (var client = server.accept()) {
					receive(client);
				} catch (Exception failure) {
					if (!server.isClosed())
						throw new IllegalStateException("Local SMTP session failed", failure);
				}
			}
		}

		private void receive(Socket client) throws Exception {
			var reader = new BufferedReader(
					new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
			var writer = new BufferedWriter(
					new OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII));
			reply(writer, "220 localhost test inbox");
			String line;
			while ((line = reader.readLine()) != null) {
				if ("QUIT".equals(line)) {
					reply(writer, "221 closing");
					return;
				}
				if ("DATA".equals(line)) {
					reply(writer, "354 End with a dot");
					var message = new StringBuilder();
					while ((line = reader.readLine()) != null && !".".equals(line))
						message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
					var bytes = message.toString().getBytes(StandardCharsets.ISO_8859_1);
					messages.add(
							new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(bytes)));
				}
				reply(writer, "250 localhost OK");
			}
		}

		private static void reply(BufferedWriter writer, String message) throws IOException {
			writer.write(message + "\r\n");
			writer.flush();
		}

		private String take(String expectedRecipient) throws Exception {
			var message = messages.poll(5, TimeUnit.SECONDS);
			assertNotNull(message, "Expected a message in the local SMTP inbox");
			assertEquals(expectedRecipient, message.getAllRecipients()[0].toString());
			return (String) message.getContent();
		}

		private String extract(String recipient, Pattern pattern) throws Exception {
			var match = pattern.matcher(take(recipient));
			assertTrue(match.find(), "Expected a code or reset link in the local message");
			return match.group(1);
		}

		private void assertEmpty() throws InterruptedException {
			assertNull(messages.poll(500, TimeUnit.MILLISECONDS));
		}
	}
}
