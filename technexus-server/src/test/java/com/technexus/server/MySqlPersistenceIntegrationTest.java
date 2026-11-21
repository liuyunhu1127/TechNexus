package com.technexus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditState;
import com.technexus.common.domain.DomainException;
import com.technexus.content.domain.ContentState;
import com.technexus.server.auth.JdbcLoginRateLimiter;
import com.technexus.server.auth.application.port.AuthAccountStore.RotationStatus;
import com.technexus.server.auth.infrastructure.JdbcAuthAccountStore;
import com.technexus.server.admin.application.AdminApplicationService;
import com.technexus.server.admin.application.BatchApplicationService;
import com.technexus.server.admin.application.FeatureFlagEvaluator;
import com.technexus.server.admin.application.ManagementApplicationService;
import com.technexus.server.admin.application.port.OperationsRepository;
import com.technexus.server.content.application.ContentApplicationService;
import com.technexus.server.community.application.CommunityApplicationService;
import com.technexus.server.community.application.DiscoveryApplicationService;
import com.technexus.server.community.application.SearchApplicationService;
import com.technexus.server.demand.application.DemandApplicationService;
import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.web.idempotency.IdempotencyStore.State;
import com.technexus.server.web.idempotency.JdbcIdempotencyStore;
import com.technexus.user.domain.UserStatus;
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
		"technexus.storage.access-key=integration-access", "technexus.storage.secret-key=integration-secret",
		"technexus.file.workers-enabled=false"})
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
	private CommunityApplicationService community;
	@Autowired
	private SearchApplicationService search;
	@Autowired
	private DiscoveryApplicationService discovery;
	@Autowired
	private FeatureFlagEvaluator featureFlags;
	@Autowired
	private DemandApplicationService demands;
	@Autowired
	private AdminApplicationService admin;
	@Autowired
	private ManagementApplicationService management;
	@Autowired
	private BatchApplicationService batches;
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
		assertEquals("14", current.getVersion().getVersion());
		var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema=DATABASE() AND table_name IN
				('tn_user','tn_auth_session','tn_article','tn_demand','tn_audit_task','tn_ai_suggestion','tn_tag_alias')
				""", Integer.class);
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
	void managesAccountsAndPostsWithoutReactivatingRevokedSessionsOrDrafts() throws Exception {
		var operator = account("operator");
		var email = "managed-" + UUID.randomUUID() + "@example.com";
		var owner = accounts.createAccount(UUID.randomUUID(), email, "$argon2id$" + "a".repeat(32), "Managed author");
		var sessionId = UUID.randomUUID();
		var refresh = hash(UUID.randomUUID().toString());
		var expires = Instant.now().plusSeconds(300);
		accounts.createSession(owner, sessionId, UUID.randomUUID(), refresh, expires);
		var user = management.listUsers(email, UserStatus.ACTIVE, 1).items().getFirst();
		assertEquals(owner.publicId(), user.id());
		assertEquals("ADMIN_SELF_ACTION_FORBIDDEN", assertThrows(DomainException.class,
				() -> management.changeUserStatus(operator, operator, false, 0, "invalid self action")).code());
		var disabled = management.changeUserStatus(operator, user.id(), false, user.version(), "suspended for review");
		assertEquals(UserStatus.DISABLED, disabled.status());
		assertFalse(accounts.isAccessSessionActive(user.id(), sessionId, owner.sessionVersion()));
		assertTrue(accounts.findByEmail(email).isEmpty());
		assertEquals(UserStatus.ACTIVE,
				management.changeUserStatus(operator, user.id(), true, disabled.version(), "review complete").status());
		assertEquals(RotationStatus.INVALID, accounts
				.rotateSession(refresh, UUID.randomUUID(), hash(UUID.randomUUID().toString()), expires).status());
		assertThrows(DomainException.class,
				() -> accounts.createSession(owner, UUID.randomUUID(), UUID.randomUUID(), new byte[32], expires));

		var title = "Managed post " + UUID.randomUUID();
		var draft = contents.create(user.id(),
				new ContentApplicationService.ContentCommand("POST", "PUBLIC", title, null, "Reviewed body"));
		assertEquals("POST_STATE_INVALID",
				assertThrows(DomainException.class,
						() -> management.changePostState(operator, draft.id(), true, draft.version(), "invalid draft"))
						.code());
		var submission = contents.submit(draft.id(), user.id(), draft.version());
		var task = audits.findById(submission.auditTaskId()).orElseThrow();
		admin.decideAuditTask(task.publicId(), operator,
				new AdminApplicationService.AuditDecisionCommand("APPROVED", "QUALITY_OK", null, task.version()));
		var published = management.listPosts(title, ContentState.PUBLISHED, 1).items().getFirst();
		assertEquals(draft.id(), published.id());
		var offline = management.changePostState(operator, published.id(), false, published.version(), "policy review");
		assertEquals(ContentState.OFFLINE, offline.state());
		assertEquals("RESOURCE_NOT_FOUND",
				assertThrows(DomainException.class, () -> contents.get(published.id(), null)).code());
		assertEquals("VERSION_CONFLICT",
				assertThrows(DomainException.class,
						() -> management.changePostState(operator, published.id(), true, published.version(), "stale"))
						.code());
		var restored = management.changePostState(operator, published.id(), true, offline.version(), "review complete");
		assertEquals(ContentState.PUBLISHED, restored.state());
		assertEquals("Reviewed body", contents.get(published.id(), null).body());
		assertTrue(operations.listOperationAudits("POST_OFFLINE", 10).stream()
				.anyMatch(entry -> operator.equals(entry.actorId())
						&& published.id().toString().equals(entry.target().get("targetId"))));
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
		var rootComment = community.createComment(provider, post.id(), null, "Root comment");
		var commentNotice = community.listNotifications(owner).getFirst();
		assertEquals("COMMENT", commentNotice.type());
		var reply = community.createComment(owner, post.id(), rootComment.id(), "Child reply");
		assertEquals("REPLY", community.listNotifications(provider).getFirst().type());
		community.deleteComment(provider, post.id(), rootComment.id(), rootComment.version());
		var thread = community.listComments(post.id());
		assertEquals("DELETED", thread.getFirst().state());
		assertEquals(rootComment.id(),
				thread.stream().filter(item -> item.id().equals(reply.id())).findFirst().orElseThrow().parentId());
		community.readNotification(owner, commentNotice.id());
		assertNotNull(community.listNotifications(owner).getFirst().readAt());

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
		var solution = contents.create(provider, new ContentApplicationService.ContentCommand("SOLUTION", "PUBLIC",
				"Demand solution", null, "Solution"));
		var knowledge = contents.create(provider, new ContentApplicationService.ContentCommand("DOCUMENT", "PUBLIC",
				"Distilled knowledge", "Lineage", "Knowledge"));
		demands.linkSolution(demand.id(), solution.id());
		demands.linkKnowledge(solution.id(), knowledge.id());
		assertEquals(5, demands.getLineage("CONTENT", post.id()).nodes().size());
		assertEquals(4, demands.getLineage("CONTENT", post.id()).edges().size());
		var contentSearch = search.search("Article", "CONTENT", "LATEST", 0, 1);
		assertTrue(contentSearch.total() >= 1);
		assertEquals(1, contentSearch.items().size());
		var demandSearch = search.search("Need help", "DEMAND", "BUDGET", 0, 20);
		assertTrue(demandSearch.items().stream().anyMatch(item -> item.id().equals(demand.id())));
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
		var categoryConfig = admin.getCategoryConfig();
		var savedCategoryConfig = admin.updateCategoryConfig(reviewer,
				new AdminApplicationService.CategoryConfigCommand(categoryConfig.categories(), categoryConfig.version(),
						"integration category config"));
		assertEquals(categoryConfig.version() + 1, savedCategoryConfig.version());
		var recommendationConfig = admin.getRecommendationConfig();
		var recommendationTarget = article.id();
		var savedRecommendationConfig = admin.updateRecommendationConfig(reviewer,
				new AdminApplicationService.RecommendationConfigCommand(
						List.of(new AdminApplicationService.RecommendationItem("CONTENT", recommendationTarget, 1)),
						recommendationConfig.version(), "integration recommendation config"));
		assertEquals(recommendationTarget, savedRecommendationConfig.items().getFirst().targetId());
		assertEquals(recommendationTarget, discovery.recommendations(10).getFirst().targetId());
		assertTrue(discovery.recommendations(10).getFirst().operated());
		var flagKey = "it.flag_" + UUID.randomUUID().toString().replace("-", "");
		var flag = admin.updateFeatureFlag(flagKey, reviewer,
				new AdminApplicationService.FeatureFlagCommand(true, Map.of("percentage", 25), 0, "alpha"));
		assertEquals(1, flag.version());
		assertEquals(25, ((Map<?, ?>) admin.getFeatureFlag(flagKey).rule()).get("percentage"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM tn_operation_audit WHERE action='FEATURE_FLAG_UPDATE' AND JSON_UNQUOTE(JSON_EXTRACT(target,'$.key'))=?",
				Integer.class, flagKey));
		var featureAudits = admin.listOperationAudits("FEATURE_FLAG_UPDATE");
		assertFalse(featureAudits.isEmpty());
		assertEquals(reviewer, featureAudits.getFirst().actorId());
		assertEquals(flagKey, featureAudits.getFirst().target().get("key"));
		var registration = admin.listFeatureFlags().stream().filter(item -> "registration.enabled".equals(item.key()))
				.findFirst().orElse(null);
		var registrationVersion = registration == null ? 0 : registration.version();
		admin.updateFeatureFlag("registration.enabled", reviewer, new AdminApplicationService.FeatureFlagCommand(false,
				Map.of("percentage", 100), registrationVersion, "integration off"));
		assertFalse(featureFlags.enabled("registration.enabled", "closed@example.com"));
		admin.updateFeatureFlag("registration.enabled", reviewer, new AdminApplicationService.FeatureFlagCommand(true,
				Map.of("percentage", 100), registrationVersion + 1, "integration restore"));
		assertTrue(featureFlags.enabled("registration.enabled", "closed@example.com"));
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
		var acceptedSuggestions = admin.listAiSuggestions("ACCEPTED");
		assertFalse(acceptedSuggestions.isEmpty());
		assertEquals(job.id(), acceptedSuggestions.getFirst().id());
		assertEquals(article.id(), acceptedSuggestions.getFirst().targetId());
		assertEquals("Article v2", contents.get(article.id(), null).title());
		assertNotNull(admin.dashboard());
		assertFalse(audits.list(null, 100).isEmpty());
		assertFalse(audits.list(AuditState.APPROVED, 100).isEmpty());

		var uploadId = UUID.randomUUID();
		var fileId = UUID.randomUUID();
		var fileHash = uniqueSha256();
		var firstUpload = files.begin(new FileRecordPort.UploadRecord(uploadId, fileId, owner, "it/" + fileId, fileHash,
				12, "text/plain", Instant.now().plusSeconds(600)));
		assertFalse(firstUpload.reused());
		assertEquals(fileId, files.requireUpload(uploadId, owner).fileId());
		files.markScanning(uploadId, owner);
		var scan = files.claimNextScan(Instant.now()).orElseThrow();
		var prematureDuplicate = new FileRecordPort.UploadRecord(UUID.randomUUID(), UUID.randomUUID(), provider,
				"it/premature-" + UUID.randomUUID(), fileHash, 12, "text/plain", Instant.now().plusSeconds(600));
		var reuseError = assertThrows(DomainException.class, () -> files.begin(prematureDuplicate));
		assertEquals("FILE_REUSE_UNAVAILABLE", reuseError.code());
		files.markAvailable(scan.fileId(), "text/plain");
		assertEquals("AVAILABLE", files.requireOwned(fileId, owner).state());
		assertEquals("text/plain", files.requireDownloadable(fileId, owner).detectedMime());

		var tagSuffix = UUID.randomUUID().toString().replace("-", "");
		var sourceTag = community.createTag(reviewer, new CommunityApplicationService.TagCreateCommand(
				"it-source-" + tagSuffix, List.of("it-alias-" + tagSuffix), "integration"));
		var targetTag = community.createTag(reviewer,
				new CommunityApplicationService.TagCreateCommand("it-target-" + tagSuffix, List.of(), "integration"));
		var tagId = sourceTag.id();
		var attached = contents.create(owner, new ContentApplicationService.ContentCommand("DOCUMENT", "PUBLIC",
				"Attached", "Summary", "Body", List.of(tagId), List.of(fileId)));
		assertEquals(List.of(tagId), contents.get(attached.id(), owner).tagIds());
		var mergedTag = community.mergeTag(sourceTag.id(), reviewer, new CommunityApplicationService.TagMergeCommand(
				targetTag.id(), sourceTag.version(), targetTag.version(), "integration merge"));
		assertEquals(targetTag.id(), contents.get(attached.id(), owner).tagIds().getFirst());
		assertTrue(mergedTag.target().aliases().contains(sourceTag.name()));
		assertTrue(contents.listTags().stream().noneMatch(tag -> tag.id().equals(sourceTag.id())));
		var batchFirst = community.createTag(reviewer,
				new CommunityApplicationService.TagCreateCommand("it-batch-a-" + tagSuffix, List.of(), "batch"));
		var batchSecond = community.createTag(reviewer,
				new CommunityApplicationService.TagCreateCommand("it-batch-b-" + tagSuffix, List.of(), "batch"));
		var batch = batches.executeTagStatusBatch(reviewer,
				new BatchApplicationService.TagStatusBatchCommand(false,
						List.of(new BatchApplicationService.TagStatusItem(batchFirst.id(), batchFirst.version()),
								new BatchApplicationService.TagStatusItem(batchSecond.id(), 99)),
						"integration partial batch"));
		assertEquals("PARTIAL_FAILED", batch.status());
		assertEquals("VERSION_CONFLICT", batches.getBatch(batch.id()).items().get(1).errorCode());
		assertFalse(community.getTag(batchFirst.id()).enabled());
		assertTrue(community.getTag(batchSecond.id()).enabled());
		assertEquals(List.of(fileId), contents.get(attached.id(), owner).fileIds());
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM tn_file_relation WHERE target_type='CONTENT' AND target_public_id=?
				""", Integer.class, bytes(attached.id())));
		assertThrows(DomainException.class, () -> files.requireDownloadable(fileId, provider));
		var attachedSubmitted = contents.submit(attached.id(), owner, attached.version());
		var attachedTask = audits.findById(attachedSubmitted.auditTaskId()).orElseThrow();
		admin.decideAuditTask(attachedTask.publicId(), reviewer, new AdminApplicationService.AuditDecisionCommand(
				"APPROVED", "QUALITY_OK", null, attachedTask.version()));
		assertEquals("text/plain", files.requireDownloadable(fileId, reviewer).detectedMime());
		assertEquals("text/plain", files.requireDownloadable(fileId, provider).detectedMime());

		var duplicateUploadId = UUID.randomUUID();
		var duplicateCandidateId = UUID.randomUUID();
		var duplicate = files.begin(new FileRecordPort.UploadRecord(duplicateUploadId, duplicateCandidateId, provider,
				"it/" + duplicateCandidateId, fileHash, 12, "text/plain", Instant.now().plusSeconds(600)));
		assertTrue(duplicate.reused());
		assertEquals(fileId, duplicate.fileId());
		assertEquals("AVAILABLE", files.markScanning(duplicateUploadId, provider));
		assertEquals("text/plain", files.requireDownloadable(fileId, provider).detectedMime());
		assertEquals("text/plain", files.requireDownloadable(fileId, reviewer).detectedMime());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tn_file_object WHERE sha256=? AND size_bytes=12",
				Integer.class, java.util.HexFormat.of().parseHex(fileHash)));
		assertEquals(2, jdbc.queryForObject(
				"SELECT COUNT(*) FROM tn_file_relation r JOIN tn_file_object f ON f.id=r.file_object_id WHERE f.public_id=?",
				Integer.class, bytes(fileId)));

		var blockedUpload = UUID.randomUUID();
		var blockedFile = UUID.randomUUID();
		files.begin(new FileRecordPort.UploadRecord(blockedUpload, blockedFile, owner, "it/" + blockedFile,
				uniqueSha256(), 13, "application/octet-stream", Instant.now().plusSeconds(600)));
		files.markScanning(blockedUpload, owner);
		var failedScan = files.claimNextScan(Instant.now()).orElseThrow();
		files.markScanFailed(failedScan.fileId(), failedScan.attempt(), Instant.now(), "temporary");
		var retry = files.claimNextScan(Instant.now().plusSeconds(1)).orElseThrow();
		files.markBlocked(retry.fileId(), "malware", "quarantine/" + retry.fileId());
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
