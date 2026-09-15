package com.technexus.file.api;

import java.util.Collection;
import java.util.UUID;

public interface FileAccessPort {
	void verifyBindings(UUID ownerId, Collection<UUID> relationIds);
}
