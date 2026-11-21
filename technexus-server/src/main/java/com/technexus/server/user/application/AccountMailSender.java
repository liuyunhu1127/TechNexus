package com.technexus.server.user.application;

import com.technexus.common.domain.DomainException;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class AccountMailSender {
	private final JavaMailSender sender;
	private final String from;
	private final URI webOrigin;

	public AccountMailSender(ObjectProvider<JavaMailSender> senders,
			@Value("${technexus.account-mail.enabled:false}") boolean enabled,
			@Value("${technexus.account-mail.from:}") String from,
			@Value("${technexus.account-mail.web-origin:}") String origin) {
		this.from = from;
		sender = enabled ? senders.getIfAvailable() : null;
		webOrigin = enabled ? checkedOrigin(origin) : null;
		if (enabled && (sender == null || from.isBlank()))
			throw new IllegalStateException("Account email needs an SMTP sender and from address");
	}

	public boolean enabled() {
		return sender != null;
	}

	public void sendCode(String recipient, String purpose, String code) {
		send(recipient, "TechNexus " + purpose, purpose + "验证码：" + code + "\n验证码 15 分钟内有效，仅可使用一次。");
	}

	public void sendResetLink(String recipient, String token) {
		var link = webOrigin.resolve("/reset-password#token=" + token);
		send(recipient, "TechNexus 密码重置", "请在 15 分钟内打开以下一次性链接设置新密码：\n" + link + "\n如非本人操作，请忽略此邮件。");
	}

	public void sendPasswordChanged(String recipient) {
		send(recipient, "TechNexus 密码已重置", "你的账号密码已重置，旧登录会话已撤销。如非本人操作，请联系支持。");
	}

	private void send(String recipient, String subject, String body) {
		if (!enabled())
			throw new DomainException("ACCOUNT_MAIL_UNAVAILABLE", "邮件服务尚未开启");
		var message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient);
		message.setSubject(subject);
		message.setText(body);
		try {
			sender.send(message);
		} catch (MailException failure) {
			throw new DomainException("ACCOUNT_MAIL_UNAVAILABLE", "暂时无法发信，请稍后重试");
		}
	}

	private static URI checkedOrigin(String value) {
		var uri = URI.create(value);
		var local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
		if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
				|| !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
				|| !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))
			throw new IllegalStateException("Account email web origin must use HTTPS or local HTTP");
		return uri;
	}
}
