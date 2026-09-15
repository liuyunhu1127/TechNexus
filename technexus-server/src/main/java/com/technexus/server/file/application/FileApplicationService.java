package com.technexus.server.file.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.UuidV7;
import com.technexus.file.domain.FileObject;
import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.ObjectStoragePort;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class FileApplicationService {
	private final FileRecordPort records;
	private final ObjectStoragePort storage;
	private final Clock clock = Clock.systemUTC();

	public FileApplicationService(FileRecordPort records, ObjectStoragePort storage) {
		this.records = records;
		this.storage = storage;
	}

	public UploadResult createUpload(UUID ownerId, UploadCommand command) {
		if (!"IMAGE".equals(command.purpose()) && !"ATTACHMENT".equals(command.purpose())) {
			throw new DomainException("FILE_PURPOSE_INVALID", "文件用途无效");
		}
		var uploadId = UuidV7.generate();
		var fileId = UuidV7.generate();
		var safeName = command.fileName().replaceAll("[^a-zA-Z0-9._-]", "_");
		if (safeName.isBlank() || safeName.length() > 255)
			throw new DomainException("FILE_NAME_INVALID", "文件名无效");
		var objectKey = "uploads/" + ownerId + "/" + fileId + "/" + safeName;
		new FileObject(fileId, command.sha256(), command.sizeBytes(), objectKey);
		var expiry = clock.instant().plusSeconds(900);
		records.begin(new FileRecordPort.UploadRecord(uploadId, fileId, ownerId, objectKey, command.sha256(),
				command.sizeBytes(), command.declaredMime(), expiry));
		var signed = storage.presignUpload(objectKey, command.declaredMime(), command.sha256());
		return new UploadResult(uploadId, fileId, "UPLOADING", signed.url().toString(), signed.requiredHeaders(),
				signed.expiresAt());
	}

	public FileResult complete(UUID ownerId, UUID uploadId) {
		var upload = records.requireUpload(uploadId, ownerId);
		if (!upload.expiresAt().isAfter(clock.instant()))
			throw new DomainException("UPLOAD_EXPIRED", "上传会话已过期");
		var metadata = storage.inspect(upload.objectKey());
		if (metadata.sizeBytes() != upload.sizeBytes())
			throw new DomainException("FILE_SIZE_MISMATCH", "对象大小与声明不一致");
		if (!upload.declaredMime().equalsIgnoreCase(metadata.contentType()))
			throw new DomainException("FILE_MIME_MISMATCH", "对象 MIME 与声明不一致");
		if (!matchesHash(upload.sha256(), metadata.sha256()))
			throw new DomainException("FILE_HASH_MISMATCH", "对象摘要与声明不一致");
		var aggregate = new FileObject(upload.fileId(), upload.sha256(), upload.sizeBytes(), upload.objectKey());
		aggregate.uploaded();
		aggregate.scanning();
		records.markScanning(uploadId, ownerId);
		return new FileResult(upload.fileId(), "SCANNING");
	}

	public DownloadResult createDownload(UUID actorId, UUID fileId) {
		var file = records.requireDownloadable(fileId, actorId);
		var signed = storage.presignDownload(file.objectKey());
		return new DownloadResult(signed.url().toString(), signed.expiresAt());
	}

	private static boolean matchesHash(String expectedHex, String observed) {
		if (observed == null)
			return false;
		if (expectedHex.equalsIgnoreCase(observed))
			return true;
		try {
			return java.util.HexFormat.of().formatHex(java.util.Base64.getDecoder().decode(observed))
					.equalsIgnoreCase(expectedHex);
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	public record UploadCommand(String fileName, long sizeBytes, String declaredMime, String sha256, String purpose) {
	}
	public record UploadResult(UUID uploadId, UUID fileId, String state, String uploadUrl,
			Map<String, String> requiredHeaders, Instant expiresAt) {
	}
	public record FileResult(UUID id, String state) {
	}
	public record DownloadResult(String url, Instant expiresAt) {
	}
}
