package com.technexus.community.api;

import com.technexus.community.domain.Tag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository {
	List<Tag> list(int limit);
	Optional<Tag> findById(UUID id);
	void add(Tag tag, UUID actorId, String reason);
	void save(Tag tag, long expectedVersion, UUID actorId, String reason);
	void merge(Tag source, Tag target, long expectedSourceVersion, long expectedTargetVersion, UUID actorId,
			String reason);
}
