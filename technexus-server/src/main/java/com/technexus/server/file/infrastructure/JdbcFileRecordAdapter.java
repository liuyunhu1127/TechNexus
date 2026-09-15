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
	public void begin(UploadRecord upload) {
		transactions.executeWithoutResult(status -> {
			jdbc.update(
					"""
							INSERT INTO tn_file_object(public_id,sha256,size_bytes,object_key,declared_mime,state,version,created_at,updated_at)
							VALUES (?,?,?,?,?,'UPLOADING',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
							""",
					bytes(upload.fileId()), HexFormat.of().parseHex(upload.sha256()), upload.sizeBytes(),
					upload.objectKey(), upload.declaredMime());
			var changed = jdbc.update(
					"""
							INSERT INTO tn_file_upload_session(public_id,owner_id,object_key,state,expires_at,created_at,updated_at)
							SELECT ?,id,?,'CREATED',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
							""",
					bytes(upload.uploadId()), upload.objectKey(), Timestamp.from(upload.expiresAt()),
					bytes(upload.ownerId()));
			if (changed != 1)
				throw new DomainException("AUTH_REQUIRED", "用户不存在或不可用");
		});
	}

	@Override
	public UploadRecord requireUpload(UUID uploadId, UUID ownerId) {
		return jdbc
				.query("""
						SELECT s.public_id upload_id,f.public_id file_id,u.public_id owner_public_id,s.object_key,
						       HEX(f.sha256) sha256,f.size_bytes,f.declared_mime,s.expires_at
						FROM tn_file_upload_session s
						JOIN tn_user u ON u.id=s.owner_id
						JOIN tn_file_object f ON f.object_key=s.object_key
						WHERE s.public_id=? AND u.public_id=? AND s.state='CREATED'
						""",
						(result, row) -> new UploadRecord(uuid(result.getBytes("upload_id")),
								uuid(result.getBytes("file_id")), uuid(result.getBytes("owner_public_id")),
								result.getString("object_key"), result.getString("sha256").toLowerCase(),
								result.getLong("size_bytes"), result.getString("declared_mime"),
								result.getTimestamp("expires_at").toInstant()),
						bytes(uploadId), bytes(ownerId))
				.stream().findFirst().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "上传会话不存在"));
	}

	@Override
	public void markScanning(UUID uploadId, UUID ownerId) {
		transactions.executeWithoutResult(status -> {
			var upload = requireUpload(uploadId, ownerId);
			var fileChanged = jdbc.update(
					"UPDATE tn_file_object SET state='SCANNING',version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state='UPLOADING'",
					bytes(upload.fileId()));
			var sessionChanged = jdbc.update(
					"UPDATE tn_file_upload_session SET state='COMPLETED',updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND state='CREATED'",
					bytes(uploadId));
			if (fileChanged != 1 || sessionChanged != 1)
				throw new DomainException("FILE_UPLOAD_CONFLICT", "上传状态已变化");
		});
	}

	@Override
	public Optional<ScanCandidate> claimNextScan(java.time.Instant now) {
		return transactions.execute(status -> {
			var candidate = jdbc
					.query("""
							SELECT public_id,object_key,declared_mime,scan_attempt
							FROM tn_file_object
							WHERE state='SCANNING' AND next_scan_at<=? AND (scan_lease_until IS NULL OR scan_lease_until<?)
							ORDER BY next_scan_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
							""",
							(result, row) -> new ScanCandidate(uuid(result.getBytes("public_id")),
									result.getString("object_key"), result.getString("declared_mime"),
									result.getInt("scan_attempt") + 1),
							Timestamp.from(now), Timestamp.from(now))
					.stream().findFirst();
			candidate.ifPresent(value -> jdbc.update("""
					UPDATE tn_file_object SET scan_attempt=?,scan_lease_until=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND state='SCANNING'
					""", value.attempt(), Timestamp.from(now.plusSeconds(120)), bytes(value.fileId())));
			return candidate;
		});
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
	public void markBlocked(UUID fileId, String reason) {
		var changed = jdbc.update("""
				UPDATE tn_file_object SET state='BLOCKED',scan_error=?,scan_lease_until=NULL,
				                          version=version+1,updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND state='SCANNING'
				""", truncate(reason), bytes(fileId));
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
	public FileRecord requireDownloadable(UUID fileId, UUID actorId) {
		return jdbc
				.query("""
						SELECT f.public_id,f.object_key,f.detected_mime
						FROM tn_file_object f
						WHERE f.public_id=? AND f.state='AVAILABLE' AND (
						    EXISTS (SELECT 1 FROM tn_file_upload_session s JOIN tn_user u ON u.id=s.owner_id WHERE s.object_key=f.object_key AND u.public_id=?)
						    OR EXISTS (SELECT 1 FROM tn_file_relation r JOIN tn_user u ON u.id=r.owner_id WHERE r.file_object_id=f.id AND u.public_id=?)
						)
						""",
						(result, row) -> new FileRecord(uuid(result.getBytes("public_id")),
								result.getString("object_key"), result.getString("detected_mime")),
						bytes(fileId), bytes(actorId), bytes(actorId))
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
}
