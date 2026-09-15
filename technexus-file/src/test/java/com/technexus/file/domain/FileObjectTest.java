package com.technexus.file.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileObjectTest {
	@Test
	void fileCannotBeDownloadedBeforeScanPasses() {
		var file = new FileObject(UUID.randomUUID(), "a".repeat(64), 10, "objects/a");
		assertThrows(DomainException.class, file::requireDownloadable);
		file.uploaded();
		file.scanning();
		file.scanPassed("text/plain");
		file.requireDownloadable();
		assertEquals(FileState.AVAILABLE, file.state());
	}

	@Test
	void validatesMetadataAndStateTransitions() {
		assertThrows(DomainException.class, () -> new FileObject(UUID.randomUUID(), "not-a-hash", 1, "objects/a"));
		assertThrows(DomainException.class, () -> new FileObject(UUID.randomUUID(), "a".repeat(64), 0, "objects/a"));
		assertThrows(DomainException.class,
				() -> new FileObject(UUID.randomUUID(), "a".repeat(64), 100L * 1024 * 1024 + 1, "objects/a"));
		var file = new FileObject(UUID.randomUUID(), "b".repeat(64), 2, "objects/b");
		assertThrows(DomainException.class, file::scanning);
		file.uploaded();
		assertThrows(DomainException.class, file::uploaded);
		file.block();
		assertEquals(FileState.BLOCKED, file.state());
	}
}
