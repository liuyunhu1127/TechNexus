package com.technexus.server.auth;

public interface LoginRateLimiter {
	void acquire(String account, String clientAddress);
}
