package com.technexus.server.web;

import com.technexus.server.file.application.FileApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
	private final FileApplicationService files;

	public FileController(FileApplicationService files) {
		this.files = files;
	}

	@PostMapping("/uploads")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> createFileUpload(Principal principal, @Valid @RequestBody UploadRequest input) {
		return Map.of("data",
				files.createUpload(RequestActor.require(principal), new FileApplicationService.UploadCommand(
						input.fileName(), input.sizeBytes(), input.declaredMime(), input.sha256(), input.purpose())));
	}

	@PostMapping("/uploads/{uploadId}/complete")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> completeFileUpload(Principal principal, @PathVariable UUID uploadId) {
		return Map.of("data", files.complete(RequestActor.require(principal), uploadId));
	}

	@PostMapping("/{fileId}/download-url")
	Map<String, Object> createFileDownloadUrl(Principal principal, @PathVariable UUID fileId) {
		return Map.of("data", files.createDownload(RequestActor.require(principal), fileId));
	}

	record UploadRequest(@NotBlank @Size(max = 255) String fileName, @Min(1) @Max(104_857_600) long sizeBytes,
			@NotBlank @Size(max = 127) String declaredMime, @Pattern(regexp = "^[0-9a-f]{64}$") String sha256,
			@Pattern(regexp = "IMAGE|ATTACHMENT") String purpose) {
	}
}
