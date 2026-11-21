package com.technexus.server.user.application;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.UuidV7;
import com.technexus.server.auth.AuthApplicationService;
import com.technexus.server.auth.LoginRateLimiter;
import com.technexus.server.auth.application.port.AuthAccountStore;
import com.technexus.server.user.application.EmailActionStore.Action;
import com.technexus.server.user.application.EmailActionStore.Kind;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountSecurityApplicationService {
	private static final Duration VALIDITY = Duration.ofMinutes(15);
	private static final SecureRandom RANDOM = new SecureRandom();
	private final UserRepository users;
	private final EmailActionStore actions;
	private final AuthAccountStore accounts;
	private final AuthApplicationService auth;
	private final PasswordEncoder passwords;
	private final LoginRateLimiter limits;
	private final AccountMailSender mail;
	private final TaskExecutor mailExecutor;
	private final TransactionTemplate transactions;
	private final Clock clock = Clock.systemUTC();

	public AccountSecurityApplicationService(UserRepository users, EmailActionStore actions, AuthAccountStore accounts,
			AuthApplicationService auth, PasswordEncoder passwords, LoginRateLimiter limits, AccountMailSender mail,
			@Qualifier("accountMailExecutor") TaskExecutor mailExecutor, PlatformTransactionManager transactions) {
		this.users = users;
		this.actions = actions;
		this.accounts = accounts;
		this.auth = auth;
		this.passwords = passwords;
		this.limits = limits;
		this.mail = mail;
		this.mailExecutor = mailExecutor;
		this.transactions = new TransactionTemplate(transactions);
	}

	public EmailSettings settings(String email, boolean verified) {
		return new EmailSettings(email, verified, auth.isBootstrapEmail(email), mail.enabled());
	}

	public EmailSettings settings(UUID actor) {
		var user = users.findById(actor).orElseThrow(AccountSecurityApplicationService::invalid);
		return settings(user.normalizedEmail(), user.emailVerified());
	}

	public UUID requestVerification(UUID actor, String password, String ip) {
		limit("verify-request", actor.toString(), ip);
		var issued = transactions.execute(status -> {
			var user = requireSelfService(actor);
			if (user.emailVerified())
				throw new DomainException("EMAIL_ALREADY_VERIFIED", "当前邮箱已验证");
			requirePassword(user, password);
			return issue(user, Kind.VERIFY_EMAIL, user.normalizedEmail());
		});
		mail.sendCode(issued.action.currentEmail(), "验证邮箱", issued.first);
		return issued.action.id();
	}

	public void verify(UUID actor, UUID id, String code, String ip) {
		limit("verify-confirm", actor.toString(), ip);
		transactions.executeWithoutResult(status -> {
			var action = lock(id, Kind.VERIFY_EMAIL, code);
			var user = requireSelfService(actor);
			requireCurrent(action, user);
			consume(action);
			user.verifyEmail();
			users.save(user);
		});
	}

	public UUID requestEmailChange(UUID actor, String password, String newEmail, String ip) {
		limit("change-request", actor.toString(), ip);
		var target = normalized(newEmail);
		var issued = transactions.execute(status -> {
			var user = requireSelfService(actor);
			requireVerified(user);
			requirePassword(user, password);
			requireAvailable(target);
			return issue(user, Kind.CHANGE_EMAIL, target);
		});
		mail.sendCode(issued.action.currentEmail(), "换绑邮箱（原邮箱）", issued.first);
		mail.sendCode(issued.action.targetEmail(), "换绑邮箱（新邮箱）", issued.second);
		return issued.action.id();
	}

	public void confirmEmailChange(UUID actor, UUID id, String oldCode, String newCode, String ip) {
		limit("change-confirm", actor.toString(), ip);
		transactions.executeWithoutResult(status -> {
			var action = lock(id, Kind.CHANGE_EMAIL, oldCode);
			var user = requireSelfService(actor);
			requireVerified(user);
			requireCurrent(action, user);
			if (!matches(action.secondDigest(), newCode))
				throw invalid();
			requireAvailable(action.targetEmail());
			consume(action);
			user.changeVerifiedEmail(action.targetEmail());
			users.save(user);
			accounts.revokeAllSessions(actor);
		});
	}

	public void requestPasswordReset(String email, String ip) {
		var target = normalized(email);
		try {
			limit("reset-request", target, ip);
		} catch (DomainException throttled) {
			if (!"AUTH_RATE_LIMITED".equals(throttled.code()))
				throw throttled;
			return;
		}
		if (!mail.enabled())
			return;
		submitMail(() -> {
			var issued = transactions.execute(status -> users.findByEmail(target)
					.filter(user -> user.status() == UserStatus.ACTIVE && user.emailVerified()
							&& !auth.isBootstrapEmail(user.normalizedEmail()))
					.map(user -> issue(user, Kind.RESET_PASSWORD, user.normalizedEmail())).orElse(null));
			if (issued != null)
				mail.sendResetLink(target, issued.action.id() + "." + issued.first);
		});
	}

	public void resetPassword(String token, String password, String ip) {
		var parts = token.split("\\.", -1);
		if (parts.length != 2)
			throw invalid();
		final UUID id;
		try {
			id = UUID.fromString(parts[0]);
		} catch (IllegalArgumentException malformed) {
			throw invalid();
		}
		limit("reset-confirm", id.toString(), ip);
		var email = transactions.execute(status -> {
			var action = lock(id, Kind.RESET_PASSWORD, parts[1]);
			var user = users.findById(action.userId())
					.filter(candidate -> candidate.status() == UserStatus.ACTIVE && candidate.emailVerified()
							&& !auth.isBootstrapEmail(candidate.normalizedEmail()))
					.orElseThrow(AccountSecurityApplicationService::invalid);
			requireCurrent(action, user);
			consume(action);
			user.resetPasswordSessions();
			users.save(user);
			accounts.updatePassword(user.publicId(), passwords.encode(password));
			accounts.revokeAllSessions(user.publicId());
			return user.normalizedEmail();
		});
		submitMail(() -> mail.sendPasswordChanged(email));
	}

	private void submitMail(Runnable operation) {
		try {
			mailExecutor.execute(() -> {
				try {
					operation.run();
				} catch (RuntimeException failure) {
					LoggerFactory.getLogger(getClass()).warn("Account email delivery failed");
				}
			});
		} catch (TaskRejectedException busy) {
			LoggerFactory.getLogger(getClass()).warn("Account email queue is full");
		}
	}

	private Issued issue(User user, Kind kind, String target) {
		if (!mail.enabled())
			throw new DomainException("ACCOUNT_MAIL_UNAVAILABLE", "邮件服务尚未开启");
		var first = newSecret();
		var second = kind == Kind.CHANGE_EMAIL ? newSecret() : null;
		var action = new Action(UuidV7.generate(), user.publicId(), kind, user.normalizedEmail(), target,
				user.sessionVersion(), digest(first), second == null ? null : digest(second),
				clock.instant().plus(VALIDITY));
		actions.replace(action);
		return new Issued(action, first, second);
	}

	private Action lock(UUID id, Kind kind, String code) {
		var action = actions.lock(id, kind).orElseThrow(AccountSecurityApplicationService::invalid);
		if (!clock.instant().isBefore(action.expiresAt()) || !matches(action.firstDigest(), code))
			throw invalid();
		return action;
	}

	private void consume(Action action) {
		if (!actions.consume(action.id()))
			throw invalid();
	}

	private static void requireCurrent(Action action, User user) {
		if (!action.userId().equals(user.publicId()) || !action.currentEmail().equals(user.normalizedEmail())
				|| action.sessionVersion() != user.sessionVersion())
			throw invalid();
	}

	private User requireSelfService(UUID actor) {
		var user = users.findById(actor).filter(candidate -> candidate.status() == UserStatus.ACTIVE)
				.orElseThrow(AccountSecurityApplicationService::invalid);
		if (auth.isBootstrapEmail(user.normalizedEmail()))
			throw new DomainException("ACCOUNT_ACTION_FORBIDDEN", "管理员账号的邮箱和密码请走人工恢复流程");
		return user;
	}

	private void requirePassword(User user, String password) {
		var account = accounts.findByEmail(user.normalizedEmail())
				.orElseThrow(AccountSecurityApplicationService::invalid);
		if (!account.publicId().equals(user.publicId()) || !passwords.matches(password, account.passwordHash()))
			throw new DomainException("CURRENT_PASSWORD_INVALID", "当前密码不正确");
	}

	private void requireAvailable(String target) {
		if (auth.isBootstrapEmail(target) || users.findByEmail(target).isPresent())
			throw new DomainException("EMAIL_UNAVAILABLE", "该邮箱无法绑定，请使用其他邮箱");
	}

	private static void requireVerified(User user) {
		if (!user.emailVerified())
			throw new DomainException("EMAIL_VERIFICATION_REQUIRED", "请先验证当前邮箱");
	}

	private void limit(String key, String subject, String ip) {
		limits.acquire("account-security-" + key + "-subject:" + subject, "any");
		limits.acquire("account-security-" + key + "-ip", ip);
	}

	private static String normalized(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static boolean matches(byte[] expected, String secret) {
		return expected != null && MessageDigest.isEqual(expected, digest(secret));
	}

	private static byte[] digest(String secret) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.US_ASCII));
		} catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 unavailable", unavailable);
		}
	}

	private static String newSecret() {
		var bytes = new byte[18];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static DomainException invalid() {
		return new DomainException("ACCOUNT_ACTION_INVALID", "验证已失效或不匹配，请检查后重试");
	}

	private record Issued(Action action, String first, String second) {
	}
	public record EmailSettings(String email, boolean verified, boolean bootstrapAdmin, boolean mailEnabled) {
	}
}
