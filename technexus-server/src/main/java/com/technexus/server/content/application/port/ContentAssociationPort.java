package com.technexus.server.content.application.port;

import java.util.List;
import java.util.UUID;

public interface ContentAssociationPort {
	void replace(UUID contentId, UUID ownerId, String contentType, List<UUID> tagIds, List<UUID> fileIds);

	ContentAssociations get(UUID contentId, String contentType);

	List<TagOption> listTags();

	record ContentAssociations(List<UUID> tagIds, List<UUID> fileIds) {
		public static ContentAssociations empty() {
			return new ContentAssociations(List.of(), List.of());
		}
	}

	record TagOption(UUID id, String name) {
	}
}
