package com.technexus.server.web;

import com.technexus.server.user.application.AccountSecurityApplicationService;
import com.technexus.server.user.application.AvatarApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AccountSecurityController {
	private static final String CODE = "[A-Za-z0-9_-]{24}";
	private final AccountSecurityApplicationService accounts;
	private final AvatarApplicationService avatars;

	public AccountSecurityController(AccountSecurityApplicationService accounts, AvatarApplicationService avatars) {
		this.accounts = accounts;
		this.avatars = avatars;
	}

	@GetMapping("/me/avatar")
	ResponseEntity<Map<String, Object>> avatar(Principal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("data", avatars.get(RequestActor.require(principal))));
	}

	@PutMapping("/me/avatar")
	Map<String, Object> changeAvatar(Principal principal, @Valid @RequestBody AvatarChange input) {
		var version = avatars.change(RequestActor.require(principal), input.fileId(), input.expectedVersion());
		return Map.of("data", Map.of("version", version));
	}

	@GetMapping("/me/security")
	ResponseEntity<Map<String, Object>> settings(Principal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("data", accounts.settings(RequestActor.require(principal))));
	}

	@PostMapping("/me/email-verification")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> requestVerification(Principal principal, HttpServletRequest request,
			@Valid @RequestBody VerificationRequest input) {
		var id = accounts.requestVerification(RequestActor.require(principal), input.password(),
				request.getRemoteAddr());
		return Map.of("data", Map.of("requestId", id));
	}

	@PostMapping("/me/email-verification/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void verify(Principal principal, HttpServletRequest request, @Valid @RequestBody Verify input) {
		accounts.verify(RequestActor.require(principal), input.requestId(), input.code(), request.getRemoteAddr());
	}

	@PostMapping("/me/email-change")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> requestChange(Principal principal, HttpServletRequest request,
			@Valid @RequestBody ChangeRequest input) {
		var id = accounts.requestEmailChange(RequestActor.require(principal), input.password(), input.email(),
				request.getRemoteAddr());
		return Map.of("data", Map.of("requestId", id));
	}

	@PostMapping("/me/email-change/confirm")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void change(Principal principal, HttpServletRequest request, @Valid @RequestBody Change input) {
		accounts.confirmEmailChange(RequestActor.require(principal), input.requestId(), input.oldCode(),
				input.newCode(), request.getRemoteAddr());
	}

	@PostMapping("/auth/forgot-password")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Map<String, Object> forgotPassword(HttpServletRequest request, @Valid @RequestBody ForgotPassword input) {
		accounts.requestPasswordReset(input.email(), request.getRemoteAddr());
		return Map.of("message", "如果该邮箱符合重置条件，我们会发送邮件。");
	}

	@PostMapping("/auth/reset-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(HttpServletRequest request, @Valid @RequestBody ResetPassword input) {
		accounts.resetPassword(input.token(), input.password(), request.getRemoteAddr());
	}

	record VerificationRequest(@NotBlank @Size(max = 128) String password) {
	}
	record AvatarChange(UUID fileId, @NotNull @Min(0) Long expectedVersion) {
	}
	record Verify(@NotNull UUID requestId, @NotBlank @Pattern(regexp = CODE) String code) {
	}
	record ChangeRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {
	}
	record Change(@NotNull UUID requestId, @NotBlank @Pattern(regexp = CODE) String oldCode,
			@NotBlank @Pattern(regexp = CODE) String newCode) {
	}
	record ForgotPassword(@NotBlank @Email @Size(max = 254) String email) {
	}
	record ResetPassword(@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}\\." + CODE) String token,
			@NotBlank @Size(min = 12, max = 128) String password) {
	}
}
