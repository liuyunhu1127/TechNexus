package com.technexus.server.security;

import com.technexus.server.web.idempotency.IdempotencyFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, BearerAuthenticationFilter bearerFilter,
			CookieMutationGuardFilter cookieMutationGuard, ObjectProvider<IdempotencyFilter> idempotencyFilters)
			throws Exception {
		var configured = http.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(headers -> {
					headers.contentSecurityPolicy(csp -> csp.policyDirectives(
							"default-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'"));
					headers.frameOptions(frame -> frame.deny());
					headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER));
					headers.permissionsPolicy(permissions -> permissions
							.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()"));
					headers.httpStrictTransportSecurity(
							hsts -> hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31536000));
				})
				.authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/contents/**", "/api/v1/demands/**", "/api/v1/search",
								"/api/v1/tags", "/api/v1/recommendations")
						.permitAll().requestMatchers("/api/v1/admin/**")
						.hasAnyAuthority("SCOPE_admin", "SCOPE_operations").anyRequest().authenticated())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((request, response, error) -> writeProblem(response,
								HttpServletResponse.SC_UNAUTHORIZED, "AUTH_REQUIRED", "需要登录"))
						.accessDeniedHandler((request, response, error) -> writeProblem(response,
								HttpServletResponse.SC_FORBIDDEN, "ACCESS_DENIED", "没有操作权限")))
				.addFilterBefore(cookieMutationGuard, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);
		var idempotencyFilter = idempotencyFilters.getIfAvailable();
		if (idempotencyFilter != null)
			configured.addFilterAfter(idempotencyFilter, BearerAuthenticationFilter.class);
		return configured.build();
	}

	private static void writeProblem(HttpServletResponse response, int status, String code, String title)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json;charset=UTF-8");
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
				+ ",\"code\":\"" + code + "\"}");
		response.flushBuffer();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}
}
