package com.technexus.server.content.infrastructure;

import com.technexus.server.content.application.port.ContentAssociationPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryContentAssociationAdapter implements ContentAssociationPort {
	private final Map<UUID, ContentAssociations> values = new ConcurrentHashMap<>();

	@Override
	public void replace(UUID contentId, UUID ownerId, String contentType, List<UUID> tagIds, List<UUID> fileIds) {
		var current = values.getOrDefault(contentId, ContentAssociations.empty());
		values.put(contentId, new ContentAssociations(tagIds == null ? current.tagIds() : List.copyOf(tagIds),
				fileIds == null ? current.fileIds() : List.copyOf(fileIds)));
	}

	@Override
	public ContentAssociations get(UUID contentId, String contentType) {
		return values.getOrDefault(contentId, ContentAssociations.empty());
	}

	@Override
	public List<TagOption> listTags() {
		return List.of();
	}
}
