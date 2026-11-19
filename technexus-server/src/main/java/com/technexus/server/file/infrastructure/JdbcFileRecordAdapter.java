package com.technexus.server.file.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.FileRecordPort;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcFileRecordAdapter implements FileRecordPort {
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcFileRecordAdapter(JdbcTemplate jdbc, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public UploadRecord begin(UploadRecord upload) {
		return transactions.execute(status -> {
			jdbc.update(
					"""
							INSERT INTO tn_file_object(public_id,sha256,size_bytes,object_key,declared_mime,state,version,created_at,updated_at)
							VALUES (?,?,?,?,?,'UPLOADING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
							ON DUPLICATE KEY UPDATE id=id
							""",
					bytes(upload.fileId()), HexFormat.of().parseHex(upload.sha256()), upload.sizeBytes(),
					upload.objectKey(), upload.declaredMime());
			var file = jdbc.queryForObject("""
					SELECT id,public_id,object_key,state FROM tn_file_object
					WHERE sha256=? AND size_bytes=? FOR UPDATE
					""",
					(result, row) -> new StoredObject(result.getLong("id"), uuid(result.getBytes("public_id")),
							result.getString("object_key"), result.getString("state")),
					HexFormat.of().parseHex(upload.sha256()), upload.sizeBytes());
			var owner = jdbc
					.query("SELECT id FROM tn_user WHERE public_id=?", (result, row) -> result.getLong("id"),
							bytes(upload.ownerId()))
					.stream().findFirst().orElseThrow(() -> new DomainException("AUTH_REQUIRED", "用户不存在或不可用"));
			if (!file.publicId().equals(upload.fileId()) && !"AVAILABLE".equals(file.state()))
				throw new DomainException("FILE_REUSE_UNAVAILABLE", "相同内容的文件尚不可安全复用");
			var sessionState = file.publicId().equals(upload.fileId()) ? "CREATED" : "REUSED";
			var sessionObjectKey = "REUSED".equals(sessionState)
					? "proofs/" + upload.ownerId() + "/" + upload.uploadId()
					: file.objectKey();
			var changed = jdbc.update("""
					INSERT INTO tn_file_upload_session(public_id,owner_id,file_object_id,relation_public_id,object_key,
					                                   state,expires_at,created_at,updated_at)
					VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
					""", bytes(upload.uploadId()), owner, file.id(), bytes(upload.uploadId()), sessionObjectKey,
					sessionState, Timestamp.from(upload.expiresAt()));
			if (changed != 1)
				throw new DomainException("FILE_UPLOAD_CONFLICT", "无法创建上传会话");
			return new UploadRecord(upload.uploadId(), file.publicId(), upload.ownerId(), sessionObjectKey,
					upload.sha256(), upload.sizeBytes(), upload.declaredMime(), upload.expiresAt(), sessionState,
					file.state());
		});
	}

	@Override
	public UploadRecord requireUpload(UUID uploadId, UUID ownerId) {
		return jdbc
				.query("""
						SELECT s.public_id upload_id,f.public_id file_id,u.public_id owner_public_id,s.object_key,
						       HEX(f.sha256) sha256,f.size_bytes,f.declared_mime,s.expires_at,s.state session_state,
						       f.state file_state
						FROM tn_file_upload_session s
						JOIN tn_user u ON u.id=s.owner_id
						JOIN tn_file_object f ON f.id=s.file_object_id
						WHERE s.public_id=? AND u.public_id=? AND s.state IN ('CREATED','REUSED')
						""",
						(result, row) -> new UploadRecord(uuid(result.getBytes("upload_id")),
								uuid(result.getBytes("file_id")), uuid(result.getBytes("owner_public_id")),
								result.getString("object_key"), result.getString("sha256").toLowerCase(),
								result.getLong("size_bytes"), result.getString("declared_mime"),
								result.getTimestamp("expires_at").toInstant(), result.getString("session_state"),
								result.getString("file_state")),
						bytes(uploadId), bytes(ownerId))
				.stream().findFirst().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "上传会话不存在"));
	}

	@Override
	public String markScanning(UUID uploadId, UUID ownerId) {
		return transactions.execute(status -> {
			var upload = requireUpload(uploadId, ownerId);
			if (!upload.reused()) {
				var fileChanged = jdbc.update(
						"UPDATE tn_file_object SET state='SCANNING',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state='UPLOADING'",
						bytes(upload.fileId()));
				if (fileChanged != 1)
					throw new DomainException("FILE_UPLOAD_CONFLICT", "文件上传状态已变化");
			}
			var relationChanged = jdbc.update(
					"""
							INSERT INTO tn_file_relation(public_id,file_object_id,owner_id,target_type,target_public_id,
							                             visibility,version,created_at,updated_at)
							SELECT relation_public_id,file_object_id,owner_id,NULL,NULL,'OWNER_ONLY',0,
							       CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
							FROM tn_file_upload_session WHERE public_id=? AND owner_id=(SELECT id FROM tn_user WHERE public_id=?)
							""",
					bytes(uploadId), bytes(ownerId));
			var sessionChanged = jdbc.update(
					"UPDATE tn_file_upload_session SET state='COMPLETED',updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state IN ('CREATED','REUSED')",
					bytes(uploadId));
			if (relationChanged != 1 || sessionChanged != 1)
				throw new DomainException("FILE_UPLOAD_CONFLICT", "上传状态已变化");
			return jdbc.queryForObject("SELECT state FROM tn_file_object WHERE public_id=?", String.class,
					bytes(upload.fileId()));
		});
	}

	@Override
	public Optional<ScanCandidate> claimNextScan(java.time.Instant now) {
		return transactions.execute(status -> {
			var candidate = jdbc.query("""
					SELECT public_id,object_key,declared_mime,HEX(sha256) sha256,scan_attempt
					FROM tn_file_object
					WHERE state='SCANNING' AND next_scan_at<=? AND (scan_lease_until IS NULL OR scan_lease_until<?)
					ORDER BY next_scan_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
					""",
					(result, row) -> new ScanCandidate(uuid(result.getBytes("public_id")),
							result.getString("object_key"), result.getString("declared_mime"),
							result.getString("sha256").toLowerCase(), result.getInt("scan_attempt") + 1),
					Timestamp.from(now), Timestamp.from(now)).stream().findFirst();
			candidate.ifPresent(value -> jdbc.update("""
					UPDATE tn_file_object SET scan_attempt=?,scan_lease_until=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND state='SCANNING'
					""", value.attempt(), Timestamp.from(now.plusSeconds(120)), bytes(value.fileId())));
			return candidate;
		});
	}

	@Override
	public Optional<ExpiredUpload> claimExpiredUpload(java.time.Instant now) {
		return transactions.execute(status -> {
			var candidate = jdbc.query("""
					SELECT s.public_id upload_id,f.public_id file_id,s.object_key,s.state
					FROM tn_file_upload_session s
					JOIN tn_file_object f ON f.id=s.file_object_id
					WHERE s.state IN ('CREATED','REUSED') AND s.expires_at<=?
					ORDER BY s.expires_at,s.id LIMIT 1 FOR UPDATE SKIP LOCKED
					""", (result, row) -> new ExpiredUpload(uuid(result.getBytes("upload_id")),
					uuid(result.getBytes("file_id")), result.getString("object_key"), result.getString("state")),
					Timestamp.from(now)).stream().findFirst();
			candidate.ifPresent(value -> jdbc.update(
					"UPDATE tn_file_upload_session SET state='CLEANING',updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state=?",
					bytes(value.uploadId()), value.previousState()));
			return candidate;
		});
	}

	@Override
	public void markUploadExpired(UUID uploadId, UUID fileId, boolean reused) {
		transactions.executeWithoutResult(status -> {
			var changed = jdbc.update(
					"UPDATE tn_file_upload_session SET state='EXPIRED',updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state='CLEANING'",
					bytes(uploadId));
			if (changed != 1)
				throw new DomainException("FILE_CLEANUP_CONFLICT", "上传清理状态已变化");
			if (!reused)
				jdbc.update("""
						UPDATE tn_file_object f SET f.state='EXPIRED',f.version=f.version+1,
						       f.updated_at=CURRENT_TIMESTAMP(6)
						WHERE f.public_id=? AND f.state='UPLOADING'
						  AND NOT EXISTS (SELECT 1 FROM tn_file_relation r WHERE r.file_object_id=f.id)
						""", bytes(fileId));
		});
	}

	@Override
	public void releaseExpiredUpload(UUID uploadId, String previousState) {
		jdbc.update(
				"UPDATE tn_file_upload_session SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state='CLEANING'",
				previousState, bytes(uploadId));
	}

	@Override
	public void markAvailable(UUID fileId, String detectedMime) {
		var changed = jdbc.update("""
				UPDATE tn_file_object SET state='AVAILABLE',detected_mime=?,scan_error=NULL,scan_lease_until=NULL,
				                          version=version+1,updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND state='SCANNING'
				""", detectedMime, bytes(fileId));
		if (changed != 1)
			throw new DomainException("FILE_SCAN_CONFLICT", "文件扫描状态已变化");
	}

	@Override
	public void markBlocked(UUID fileId, String reason, String objectKey) {
		var changed = jdbc.update("""
				UPDATE tn_file_object SET state='BLOCKED',object_key=?,scan_error=?,scan_lease_until=NULL,
				                          version=version+1,updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND state='SCANNING'
				""", objectKey, truncate(reason), bytes(fileId));
		if (changed != 1)
			throw new DomainException("FILE_SCAN_CONFLICT", "文件扫描状态已变化");
	}

	@Override
	public void markScanFailed(UUID fileId, int nextAttempt, java.time.Instant nextAttemptAt, String error) {
		var changed = jdbc.update("""
				UPDATE tn_file_object SET scan_attempt=?,next_scan_at=?,scan_error=?,scan_lease_until=NULL,
				                          updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND state='SCANNING'
				""", nextAttempt, Timestamp.from(nextAttemptAt), truncate(error), bytes(fileId));
		if (changed != 1)
			throw new DomainException("FILE_SCAN_CONFLICT", "文件扫描状态已变化");
	}

	@Override
	public FileStatus requireOwned(UUID fileId, UUID actorId) {
		return jdbc
				.query("""
						SELECT f.public_id,f.state,f.detected_mime
						FROM tn_file_object f
						WHERE f.public_id=?
						  AND EXISTS (SELECT 1 FROM tn_file_relation r JOIN tn_user u ON u.id=r.owner_id
						              WHERE r.file_object_id=f.id AND u.public_id=?)
						""",
						(result, row) -> new FileStatus(uuid(result.getBytes("public_id")), result.getString("state"),
								result.getString("detected_mime")),
						bytes(fileId), bytes(actorId))
				.stream().findFirst().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "文件不存在"));
	}

	@Override
	public FileRecord requireDownloadable(UUID fileId, UUID actorId) {
		return jdbc
				.query("""
						SELECT f.public_id,f.object_key,f.detected_mime
						FROM tn_file_object f
						WHERE f.public_id=? AND f.state='AVAILABLE'
						  AND (EXISTS (SELECT 1 FROM tn_file_relation r JOIN tn_user u ON u.id=r.owner_id
						               WHERE r.file_object_id=f.id AND u.public_id=?)
						       OR EXISTS (SELECT 1 FROM tn_file_relation r
						                  WHERE r.file_object_id=f.id AND r.target_type='CONTENT' AND r.visibility='PUBLIC'
						                    AND (EXISTS (SELECT 1 FROM tn_article a WHERE a.public_id=r.target_public_id
						                                 AND a.state='PUBLISHED' AND a.visibility='PUBLIC')
						                         OR EXISTS (SELECT 1 FROM tn_post p WHERE p.public_id=r.target_public_id
						                                    AND p.state='PUBLISHED' AND p.visibility='PUBLIC'))))
						""",
						(result, row) -> new FileRecord(uuid(result.getBytes("public_id")),
								result.getString("object_key"), result.getString("detected_mime")),
						bytes(fileId), bytes(actorId))
				.stream().findFirst().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "文件不存在或无访问权"));
	}

	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
	private static String truncate(String value) {
		if (value == null || value.isBlank())
			return "UNKNOWN_SCAN_ERROR";
		return value.length() <= 1000 ? value : value.substring(0, 1000);
	}

	private record StoredObject(long id, UUID publicId, String objectKey, String state) {
	}
}
