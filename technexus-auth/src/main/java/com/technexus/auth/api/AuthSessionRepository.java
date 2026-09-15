package com.technexus.auth.api;

import com.technexus.auth.domain.AuthSession;
import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository {
	Optional<AuthSession> findBySid(UUID sid);
	void save(AuthSession session);
	void revokeFamily(UUID tokenFamily);
}
