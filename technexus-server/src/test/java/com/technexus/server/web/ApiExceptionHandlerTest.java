package com.technexus.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.technexus.common.domain.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void mapsObjectStorageOutageToServiceUnavailable() {
		var request = new MockHttpServletRequest("POST", "/api/v1/files/uploads/1/complete");

		var problem = handler.domain(new DomainException("OBJECT_STORAGE_UNAVAILABLE", "对象存储暂不可用"), request);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), problem.getStatus());
		assertEquals("OBJECT_STORAGE_UNAVAILABLE", problem.getProperties().get("code"));
	}

	@Test
	void mapsMalwareScannerOutageToServiceUnavailable() {
		var request = new MockHttpServletRequest("POST", "/internal/file-scan");

		var problem = handler.domain(new DomainException("FILE_SCAN_UNAVAILABLE", "扫描服务暂不可用"), request);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), problem.getStatus());
		assertEquals("FILE_SCAN_UNAVAILABLE", problem.getProperties().get("code"));
	}
}
