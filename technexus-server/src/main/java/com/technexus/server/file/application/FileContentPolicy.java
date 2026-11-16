package com.technexus.server.file.application;

import com.technexus.common.domain.DomainException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FileContentPolicy {
	private static final int SAMPLE_BYTES = 8192;
	private static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
	private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "log", "md", "markdown", "csv", "json", "xml",
			"yaml", "yml", "java", "kt", "kts", "js", "mjs", "cjs", "ts", "tsx", "jsx", "css", "html", "htm", "sql",
			"sh", "ps1", "properties", "toml", "ini");
	private static final Map<String, Set<String>> BINARY_TYPES = Map.ofEntries(Map.entry("png", Set.of("image/png")),
			Map.entry("jpg", Set.of("image/jpeg")), Map.entry("jpeg", Set.of("image/jpeg")),
			Map.entry("gif", Set.of("image/gif")), Map.entry("webp", Set.of("image/webp")),
			Map.entry("pdf", Set.of("application/pdf")),
			Map.entry("zip", Set.of("application/zip", "application/x-zip-compressed")),
			Map.entry("gz", Set.of("application/gzip", "application/x-gzip")),
			Map.entry("gzip", Set.of("application/gzip", "application/x-gzip")),
			Map.entry("docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document")),
			Map.entry("xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")),
			Map.entry("pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation")));

	private FileContentPolicy() {
	}

	static void validateDeclared(String fileName, long sizeBytes, String declaredMime, String purpose) {
		var extension = extension(fileName);
		var mime = normalize(declaredMime);
		if ("IMAGE".equals(purpose) && sizeBytes > IMAGE_MAX_BYTES)
			throw new DomainException("FILE_SIZE_INVALID", "图片大小不能超过 10 MB");
		if (TEXT_EXTENSIONS.contains(extension)) {
			if (!isTextDeclaration(mime))
				throw new DomainException("FILE_TYPE_INVALID", "文件扩展名与 MIME 不匹配");
			if ("IMAGE".equals(purpose))
				throw new DomainException("FILE_TYPE_INVALID", "图片用途只允许图片格式");
			return;
		}
		var allowed = BINARY_TYPES.get(extension);
		if (allowed == null || !allowed.contains(mime))
			throw new DomainException("FILE_TYPE_INVALID", "不支持的文件类型或扩展名与 MIME 不匹配");
		if ("IMAGE".equals(purpose) && !mime.startsWith("image/"))
			throw new DomainException("FILE_TYPE_INVALID", "图片用途只允许图片格式");
	}

	static ValidatedContent validateContent(InputStream source, String objectKey, String declaredMime)
			throws Exception {
		var stream = new PushbackInputStream(source, SAMPLE_BYTES);
		var sample = stream.readNBytes(SAMPLE_BYTES);
		stream.unread(sample);
		var extension = extension(objectKey);
		var declared = normalize(declaredMime);
		var detected = detect(sample);
		if (TEXT_EXTENSIONS.contains(extension)) {
			if (!"text/plain".equals(detected))
				throw new DomainException("FILE_MAGIC_MISMATCH", "文件内容与声明的文本类型不匹配");
			if ("json".equals(extension) && !looksLikeJson(sample))
				throw new DomainException("FILE_MAGIC_MISMATCH", "JSON 文件内容格式无效");
			return new ValidatedContent(canonicalTextMime(extension, declared), stream);
		}
		var expected = expectedMagic(extension);
		if (!expected.equals(detected))
			throw new DomainException("FILE_MAGIC_MISMATCH", "文件 Magic Number 与扩展名或 MIME 不匹配");
		return new ValidatedContent(declared, stream);
	}

	static boolean isDeterministicRejection(DomainException error) {
		return "FILE_MAGIC_MISMATCH".equals(error.code()) || "FILE_TYPE_INVALID".equals(error.code());
	}

	private static String detect(byte[] value) {
		if (startsWith(value, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
			return "image/png";
		if (startsWith(value, 0xff, 0xd8, 0xff))
			return "image/jpeg";
		if (startsWithAscii(value, "GIF87a") || startsWithAscii(value, "GIF89a"))
			return "image/gif";
		if (value.length >= 12 && startsWithAscii(value, "RIFF") && asciiAt(value, 8, "WEBP"))
			return "image/webp";
		if (startsWithAscii(value, "%PDF-"))
			return "application/pdf";
		if (startsWith(value, 0x50, 0x4b, 0x03, 0x04) || startsWith(value, 0x50, 0x4b, 0x05, 0x06)
				|| startsWith(value, 0x50, 0x4b, 0x07, 0x08))
			return "application/zip";
		if (startsWith(value, 0x1f, 0x8b))
			return "application/gzip";
		return isUtf8Text(value) ? "text/plain" : "application/octet-stream";
	}

	private static String expectedMagic(String extension) {
		return switch (extension) {
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "pdf" -> "application/pdf";
			case "zip", "docx", "xlsx", "pptx" -> "application/zip";
			case "gz", "gzip" -> "application/gzip";
			default -> throw new DomainException("FILE_TYPE_INVALID", "不支持的文件类型");
		};
	}

	private static boolean isTextDeclaration(String mime) {
		return mime.startsWith("text/") || Set.of("application/json", "application/xml", "application/yaml",
				"application/x-yaml", "application/octet-stream").contains(mime);
	}

	private static String canonicalTextMime(String extension, String declared) {
		return switch (extension) {
			case "json" -> "application/json";
			case "xml" -> "application/xml";
			case "yaml", "yml" -> "application/yaml";
			case "md", "markdown" -> "text/markdown";
			case "csv" -> "text/csv";
			default -> declared.startsWith("text/") ? declared : "text/plain";
		};
	}

	private static boolean looksLikeJson(byte[] sample) {
		var text = new String(sample, StandardCharsets.UTF_8).stripLeading();
		return text.startsWith("{") || text.startsWith("[");
	}

	private static boolean isUtf8Text(byte[] value) {
		for (var item : value) {
			var unsigned = item & 0xff;
			if (unsigned == 0 || unsigned < 0x09 || unsigned > 0x0d && unsigned < 0x20)
				return false;
		}
		try {
			StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value));
			return true;
		} catch (CharacterCodingException ignored) {
			return false;
		}
	}

	private static boolean startsWith(byte[] value, int... prefix) {
		if (value.length < prefix.length)
			return false;
		for (var index = 0; index < prefix.length; index++)
			if ((value[index] & 0xff) != prefix[index])
				return false;
		return true;
	}

	private static boolean startsWithAscii(byte[] value, String prefix) {
		return asciiAt(value, 0, prefix);
	}

	private static boolean asciiAt(byte[] value, int offset, String expected) {
		var bytes = expected.getBytes(StandardCharsets.US_ASCII);
		if (value.length < offset + bytes.length)
			return false;
		for (var index = 0; index < bytes.length; index++)
			if (value[offset + index] != bytes[index])
				return false;
		return true;
	}

	private static String extension(String value) {
		var fileName = value.substring(value.lastIndexOf('/') + 1);
		var dot = fileName.lastIndexOf('.');
		if (dot <= 0 || dot == fileName.length() - 1)
			throw new DomainException("FILE_TYPE_INVALID", "文件必须包含受支持的扩展名");
		return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private static String normalize(String value) {
		if (value == null)
			return "";
		return value.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
	}

	record ValidatedContent(String detectedMime, InputStream stream) {
	}
}
