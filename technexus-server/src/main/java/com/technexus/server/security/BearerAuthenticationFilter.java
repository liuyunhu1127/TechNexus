package com.technexus.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import com.technexus.server.auth.application.port.AuthAccountStore;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BearerAuthenticationFilter extends OncePerRequestFilter {
	private final BearerTokenService tokens;
	private final AuthAccountStore sessions;

	public BearerAuthenticationFilter(BearerTokenService tokens, AuthAccountStore sessions) {
		this.tokens = tokens;
		this.sessions = sessions;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.startsWith("Bearer ")) {
			try {
				var claims = tokens.verify(authorization.substring(7));
				if (!sessions.isAccessSessionActive(claims.userId(), claims.sid(), claims.sessionVersion())) {
					throw new com.technexus.common.domain.DomainException("TOKEN_REVOKED", "访问令牌所属会话已失效");
				}
				var authorities = Arrays.stream(claims.scope().split(" ")).filter(value -> !value.isBlank())
						.map(value -> new SimpleGrantedAuthority("SCOPE_" + value)).toList();
				var authentication = new UsernamePasswordAuthenticationToken(claims.userId().toString(), claims,
						authorities);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			} catch (RuntimeException ignored) {
				SecurityContextHolder.clearContext();
			}
		}
		chain.doFilter(request, response);
	}
}
