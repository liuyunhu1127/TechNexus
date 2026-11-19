package com.technexus.server.file.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileContentPolicyTest {
	@Test
	void acceptsSupportedDeclarationsAndEnforcesImageLimit() {
		FileContentPolicy.validateDeclared("notes.txt", 128, "text/plain", "ATTACHMENT");
		FileContentPolicy.validateDeclared("diagram.png", 1024, "image/png", "IMAGE");
		assertEquals("FILE_SIZE_INVALID", assertThrows(DomainException.class,
				() -> FileContentPolicy.validateDeclared("large.png", 10L * 1024 * 1024 + 1, "image/png", "IMAGE"))
				.code());
		assertEquals("FILE_TYPE_INVALID", assertThrows(DomainException.class,
				() -> FileContentPolicy.validateDeclared("payload.exe", 128, "application/octet-stream", "ATTACHMENT"))
				.code());
	}

	@Test
	void detectsMagicAndReplaysEveryByte() throws Exception {
		var png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
		var validated = FileContentPolicy.validateContent(new ByteArrayInputStream(png), "uploads/image.png",
				"image/png");
		assertEquals("image/png", validated.detectedMime());
		assertEquals(java.util.HexFormat.of().formatHex(png),
				java.util.HexFormat.of().formatHex(validated.stream().readAllBytes()));
	}

	@Test
	void rejectsMagicMismatchAndInvalidJson() {
		var text = "not a png".getBytes(StandardCharsets.UTF_8);
		assertEquals("FILE_MAGIC_MISMATCH", assertThrows(DomainException.class, () -> FileContentPolicy
				.validateContent(new ByteArrayInputStream(text), "uploads/image.png", "image/png")).code());
		assertEquals("FILE_MAGIC_MISMATCH",
				assertThrows(DomainException.class, () -> FileContentPolicy
						.validateContent(new ByteArrayInputStream(text), "uploads/config.json", "application/json"))
						.code());
	}
}
