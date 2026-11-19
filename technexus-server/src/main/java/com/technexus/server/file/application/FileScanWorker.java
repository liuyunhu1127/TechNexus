package com.technexus.server.file.application;

import com.technexus.common.domain.DomainException;
import com.technexus.server.file.application.port.FileRecordPort;
import com.technexus.server.file.application.port.MalwareScannerPort;
import com.technexus.server.file.application.port.ObjectStoragePort;
import java.time.Clock;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "technexus.file", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class FileScanWorker {
	private static final int MAX_ATTEMPTS = 5;
	private final FileRecordPort records;
	private final ObjectStoragePort storage;
	private final MalwareScannerPort scanner;
	private final Clock clock;

	@Autowired
	public FileScanWorker(FileRecordPort records, ObjectStoragePort storage, MalwareScannerPort scanner) {
		this(records, storage, scanner, Clock.systemUTC());
	}

	FileScanWorker(FileRecordPort records, ObjectStoragePort storage, MalwareScannerPort scanner, Clock clock) {
		this.records = records;
		this.storage = storage;
		this.scanner = scanner;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${technexus.file.scan.poll-ms:5000}")
	public void poll() {
		for (int count = 0; count < 10 && scanNext(); count++) {
			// Bound every poll so one worker cannot monopolize the scheduler.
		}
	}

	public boolean scanNext() {
		var candidate = records.claimNextScan(clock.instant());
		if (candidate.isEmpty())
			return false;
		var file = candidate.get();
		try {
			storage.inspect(file.objectKey());
			try (var content = storage.openContent(file.objectKey())) {
				var validated = FileContentPolicy.validateContent(content, file.objectKey(), file.declaredMime());
				var digest = MessageDigest.getInstance("SHA-256");
				var result = scanner.scan(new DigestInputStream(validated.stream(), digest));
				if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(file.sha256())) {
					var quarantineKey = "quarantine/" + file.fileId();
					storage.quarantine(file.objectKey(), quarantineKey);
					records.markBlocked(file.fileId(), "FILE_HASH_MISMATCH:实际内容摘要与声明不一致", quarantineKey);
					return true;
				}
				if (result.clean())
					records.markAvailable(file.fileId(), validated.detectedMime());
				else {
					var quarantineKey = "quarantine/" + file.fileId();
					storage.quarantine(file.objectKey(), quarantineKey);
					records.markBlocked(file.fileId(), "MALWARE:" + safe(result.signature()), quarantineKey);
				}
			}
		} catch (DomainException error) {
			if (FileContentPolicy.isDeterministicRejection(error)) {
				var quarantineKey = "quarantine/" + file.fileId();
				storage.quarantine(file.objectKey(), quarantineKey);
				records.markBlocked(file.fileId(), error.code() + ":" + safe(error.getMessage()), quarantineKey);
				return true;
			}
			handleFailure(file, error);
		} catch (Exception error) {
			handleFailure(file, error);
		}
		return true;
	}

	private void handleFailure(FileRecordPort.ScanCandidate file, Exception error) {
		if (file.attempt() >= MAX_ATTEMPTS) {
			records.markBlocked(file.fileId(), "SCAN_FAILED_FINAL:" + safe(error.getMessage()), file.objectKey());
		} else {
			var delay = Math.min(3600, 30L << Math.min(file.attempt() - 1, 6));
			records.markScanFailed(file.fileId(), file.attempt(), clock.instant().plusSeconds(delay),
					safe(error.getMessage()));
		}
	}

	private static String safe(String value) {
		if (value == null || value.isBlank())
			return "UNKNOWN";
		return value.length() <= 900 ? value : value.substring(0, 900);
	}
}
