package com.technexus.server.security;

import com.technexus.server.web.idempotency.IdempotencyFilter;
import jakarta.servlet.http.HttpServletResponse;
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

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, BearerAuthenticationFilter bearerFilter,
			CookieMutationGuardFilter cookieMutationGuard, ObjectProvider<IdempotencyFilter> idempotencyFilters)
			throws Exception {
		var configured = http.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.requestMatchers("/actuator/health", "/api/v1/auth/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/contents/**", "/api/v1/demands/**", "/api/v1/search")
						.permitAll().requestMatchers("/api/v1/admin/**")
						.hasAnyAuthority("SCOPE_admin", "SCOPE_operations").anyRequest().authenticated())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint(
								(request, response, error) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
						.accessDeniedHandler(
								(request, response, error) -> response.sendError(HttpServletResponse.SC_FORBIDDEN)))
				.addFilterBefore(cookieMutationGuard, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);
		var idempotencyFilter = idempotencyFilters.getIfAvailable();
		if (idempotencyFilter != null)
			configured.addFilterAfter(idempotencyFilter, BearerAuthenticationFilter.class);
		return configured.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}
}
