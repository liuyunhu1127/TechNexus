package com.technexus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditState;
import com.technexus.common.domain.DomainException;
import com.technexus.server.auth.JdbcLoginRateLimiter;
import com.technexus.server.auth.application.port.AuthAccountStore.RotationStatus;
import com.technexus.server.auth.infrastructure.JdbcAuthAccountStore;
import com.technexus.server.admin.application.AdminApplicationService;
import com.technexus.server.admin.application.port.OperationsRepository;
import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.demand.application.DemandApplicationService;
import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.web.idempotency.IdempotencyStore.State;
import com.technexus.server.web.idempotency.JdbcIdempotencyStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"technexus.security.jwt-secret=integration-secret-that-is-at-least-thirty-two-bytes-long",
		"technexus.storage.access-key=integration-access", "technexus.storage.secret-key=integration-secret"})
@EnabledIfEnvironmentVariable(named = "TECHNEXUS_MYSQL_IT", matches = "true")
class MySqlPersistenceIntegrationTest {
	@Autowired
	private Flyway flyway;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JdbcAuthAccountStore accounts;
	@Autowired
	private ContentApplicationService contents;
	@Autowired
	private DemandApplicationService demands;
	@Autowired
	private AdminApplicationService admin;
	@Autowired
	private AuditTaskRepository audits;
	@Autowired
	private OperationsRepository operations;
	@Autowired
	private FileRecordPort files;
	@Autowired
	private JdbcIdempotencyStore idempotency;
	@Autowired
	private JdbcLoginRateLimiter rateLimiter;

	@Test
	void appliesAllMigrationsOnMySql84() {
		var current = flyway.info().current();
		assertEquals("11", current.getVersion().getVersion());
		var count = jdbc.queryForObject(
				"""
						SELECT COUNT(*) FROM information_schema.tables
						WHERE table_schema=DATABASE() AND table_name IN
						('tn_user','tn_auth_session','tn_article','tn_demand','tn_audit_task','tn_ai_suggestion','tn_rate_limit')
						""",
				Integer.class);
		assertEquals(7, count);
	}

	@Test
	void concurrentRefreshAllowsOneRotationAndDetectsReuse() throws Exception {
		var account = accounts.createAccount(UUID.randomUUID(), "mysql-" + UUID.randomUUID() + "@example.com",
				"$argon2id$" + "a".repeat(32), "MySQL IT");
		var family = UUID.randomUUID();
		var currentHash = hash(UUID.randomUUID().toString());
		accounts.createSession(account, UUID.randomUUID(), family, currentHash, Instant.now().plusSeconds(300));
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> rotate(ready, start, currentHash));
			var second = executor.submit(() -> rotate(ready, start, currentHash));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();
			var statuses = Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
			assertEquals(Set.of(RotationStatus.ROTATED, RotationStatus.REUSED), statuses);
		}
		var active = jdbc.queryForObject(
				"SELECT COUNT(*) FROM tn_auth_session WHERE token_family=? AND status='ACTIVE'", Integer.class,
				bytes(family));
		assertEquals(0, active);
	}

	@Test
	void roundTripsCoreBusinessWorkflowsThroughJdbcAdapters() throws Exception {
		var owner = account("owner");
		var reviewer = account("reviewer");
		var provider = account("provider");

		var article = contents.create(owner,
				new ContentApplicationService.ContentCommand("DOCUMENT", "PUBLIC", "Article", "Summary", "Body"));
		article = contents.update(article.id(), owner, article.version(),
				new ContentApplicationService.ContentCommand(null, "PUBLIC", "Article v2", "Summary", "Body v2"));
		var submittedArticle = contents.submit(article.id(), owner, article.version());
		var articleTask = audits.findById(submittedArticle.auditTaskId()).orElseThrow();
		admin.decideAuditTask(articleTask.publicId(), reviewer, new AdminApplicationService.AuditDecisionCommand(
				"APPROVED", "QUALITY_OK", null, articleTask.version()));
		assertEquals("PUBLISHED", contents.get(article.id(), null).state());
		assertFalse(contents.listPublic().isEmpty());

		var post = contents.create(owner,
				new ContentApplicationService.ContentCommand("PROBLEM", "PUBLIC", "Problem", null, "Details"));
		var submittedPost = contents.submit(post.id(), owner, post.version());
		var postTask = audits.findById(submittedPost.auditTaskId()).orElseThrow();
		admin.decideAuditTask(postTask.publicId(), reviewer,
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, postTask.version()));
		assertEquals("PUBLISHED", contents.get(post.id(), null).state());

		var demand = demands.createFromContent(post.id(), owner,
				new DemandApplicationService.DemandCommand("Need help", "Build it", new BigDecimal("10.00"),
						new BigDecimal("20.00"), Instant.now().plusSeconds(3600), "PUBLIC"));
		var submittedDemand = demands.submit(demand.id(), owner, demand.version());
		var demandTask = audits.findById(submittedDemand.auditTaskId()).orElseThrow();
		admin.decideAuditTask(demandTask.publicId(), reviewer,
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, demandTask.version()));
		admin.confirmPrice(reviewer, "DEMAND", demand.id(),
				new AdminApplicationService.PriceCommand("FREE", BigDecimal.ZERO, Instant.now(), "Alpha free", 0));
		assertFalse(demands.listPublic().isEmpty());
		assertFalse(demands.getLineage("DEMAND", demand.id()).edges().isEmpty());

		var proposal = demands.createProposal(demand.id(), provider, new DemandApplicationService.ProposalCommand(
				"Implementation", List.of("Java", "Vue"), 7, new BigDecimal("15.00")));
		var negotiating = demands.transition(demand.id(), owner, 3, "START_NEGOTIATION", null);
		var confirmed = demands.transition(demand.id(), owner, negotiating.version(), "CONFIRM_PROPOSAL",
				proposal.id());
		var started = demands.transition(demand.id(), owner, confirmed.version(), "START_WORK", null);
		assertEquals("DELIVERED",
				demands.transition(demand.id(), provider, started.version(), "DELIVER", null).state());

		var configKey = "it.config_" + UUID.randomUUID().toString().replace("-", "");
		var config = admin.updateConfig(configKey, reviewer,
				new AdminApplicationService.ConfigCommand(10, 0, "initialize"));
		admin.updateConfig(configKey, reviewer,
				new AdminApplicationService.ConfigCommand(12, config.version(), "adjust"));
		var idempotencyKey = "it-" + UUID.randomUUID();
		var requestHash = hash("request-one");
		var reservation = idempotency.reserve(owner, idempotencyKey, "POST", "/api/v1/contents", requestHash);
		assertEquals(State.ACQUIRED, reservation.state());
		idempotency.complete(reservation.id(), 201, "{\"data\":{\"id\":\"one\"}}");
		assertEquals(State.REPLAY,
				idempotency.reserve(owner, idempotencyKey, "POST", "/api/v1/contents", requestHash).state());
		assertEquals(State.CONFLICT,
				idempotency.reserve(owner, idempotencyKey, "POST", "/api/v1/contents", hash("request-two")).state());
		var rateAccount = "rate-" + UUID.randomUUID() + "@example.com";
		for (var attempt = 0; attempt < 10; attempt++)
			rateLimiter.acquire(rateAccount, "127.0.0.1");
		var rateError = assertThrows(DomainException.class, () -> rateLimiter.acquire(rateAccount, "127.0.0.1"));
		assertEquals("AUTH_RATE_LIMITED", rateError.code());
		jdbc.update("UPDATE tn_ai_suggestion SET state='FAILED' WHERE state IN ('QUEUED','RUNNING')");
		var suggestion = admin.createAiSuggestion(
				new AdminApplicationService.AiSuggestionCommand("SUMMARY", "CONTENT", article.id(), UUID.randomUUID()));
		var claimAt = Instant.now().plusSeconds(5);
		var job = operations.claimNextSuggestion(claimAt, claimAt.plusSeconds(60)).orElseThrow();
		assertEquals(suggestion.id(), job.id());
		operations.completeSuggestion(job.id(), job.version(), Map.of("summary", "Advisory only"));
		var completed = operations.findSuggestion(job.id()).orElseThrow();
		admin.decideAiSuggestion(job.id(), reviewer,
				new AdminApplicationService.AiDecisionCommand("ACCEPT", null, completed.version()));
		assertNotNull(admin.dashboard());
		assertFalse(audits.list(null, 100).isEmpty());
		assertFalse(audits.list(AuditState.APPROVED, 100).isEmpty());

		var uploadId = UUID.randomUUID();
		var fileId = UUID.randomUUID();
		files.begin(new FileRecordPort.UploadRecord(uploadId, fileId, owner, "it/" + fileId, uniqueSha256(), 12,
				"text/plain", Instant.now().plusSeconds(600)));
		assertEquals(fileId, files.requireUpload(uploadId, owner).fileId());
		files.markScanning(uploadId, owner);
		var scan = files.claimNextScan(Instant.now()).orElseThrow();
		files.markAvailable(scan.fileId(), "text/plain");
		assertEquals("text/plain", files.requireDownloadable(fileId, owner).detectedMime());

		var blockedUpload = UUID.randomUUID();
		var blockedFile = UUID.randomUUID();
		files.begin(new FileRecordPort.UploadRecord(blockedUpload, blockedFile, owner, "it/" + blockedFile,
				uniqueSha256(), 13, "application/octet-stream", Instant.now().plusSeconds(600)));
		files.markScanning(blockedUpload, owner);
		var failedScan = files.claimNextScan(Instant.now()).orElseThrow();
		files.markScanFailed(failedScan.fileId(), failedScan.attempt(), Instant.now(), "temporary");
		var retry = files.claimNextScan(Instant.now().plusSeconds(1)).orElseThrow();
		files.markBlocked(retry.fileId(), "malware");
	}

	private static String uniqueSha256() {
		return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
	}

	private RotationStatus rotate(CountDownLatch ready, CountDownLatch start, byte[] currentHash) throws Exception {
		ready.countDown();
		assertTrue(start.await(5, TimeUnit.SECONDS));
		var nextHash = hash(UUID.randomUUID().toString());
		return accounts.rotateSession(currentHash, UUID.randomUUID(), nextHash, Instant.now().plusSeconds(600))
				.status();
	}

	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}

	private static byte[] hash(String value) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
	}

	private UUID account(String prefix) {
		var id = UUID.randomUUID();
		accounts.createAccount(id, prefix + "-" + id + "@example.com", "$argon2id$" + "a".repeat(32), prefix);
		return id;
	}
}
