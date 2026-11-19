package com.technexus.server.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {
	private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

	@Test
	void echoesSafeRequestIdAndClearsMdcAfterRequest() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/contents");
		request.addHeader(RequestCorrelationFilter.HEADER, "client-request-1234");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		assertEquals("client-request-1234", response.getHeader(RequestCorrelationFilter.HEADER));
		assertNull(MDC.get("requestId"));
		assertNull(MDC.get("traceId"));
	}

	@Test
	void replacesUnsafeRequestIdInsteadOfLoggingUntrustedControlCharacters() throws Exception {
		var request = new MockHttpServletRequest("GET", "/actuator/health");
		request.addHeader(RequestCorrelationFilter.HEADER, "bad\nvalue");
		var response = new MockHttpServletResponse();

		filter.doFilter(request, response, new MockFilterChain());

		var generated = response.getHeader(RequestCorrelationFilter.HEADER);
		assertNotEquals("bad\nvalue", generated);
		assertFalse(generated.contains("\n"));
		assertEquals(36, generated.length());
	}
}
