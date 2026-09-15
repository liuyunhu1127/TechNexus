package com.technexus.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieMutationGuardFilterTest {
	private final CookieMutationGuardFilter filter = new CookieMutationGuardFilter("https://technexus.example");

	@Test
	void permitsRefreshWhenOriginAndDoubleSubmitTokenMatch() throws Exception {
		var request = request("https://technexus.example", "same-token", "same-token");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(200, response.getStatus());
		assertEquals(request, chain.getRequest());
	}

	@Test
	void rejectsRefreshWhenOriginOrTokenDoesNotMatch() throws Exception {
		var request = request("https://evil.example", "header-token", "cookie-token");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertEquals(403, response.getStatus());
		assertEquals("application/problem+json;charset=UTF-8", response.getContentType());
	}

	private static MockHttpServletRequest request(String origin, String header, String cookie) {
		var request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
		request.addHeader("Origin", origin);
		request.addHeader("X-CSRF-Token", header);
		request.setCookies(new Cookie("tn_csrf", cookie));
		return request;
	}
}
