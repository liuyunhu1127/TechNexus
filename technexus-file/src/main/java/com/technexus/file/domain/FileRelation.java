package com.technexus.file.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.Visibility;
import java.util.Objects;
import java.util.UUID;

public final class FileRelation extends AggregateRoot {
	private final UUID publicId;
	private final UUID fileObjectId;
	private final UUID ownerId;
	private final TargetRef target;
	private Visibility visibility;

	public FileRelation(UUID publicId, UUID fileObjectId, UUID ownerId, TargetRef target, Visibility visibility) {
		this.publicId = Objects.requireNonNull(publicId);
		this.fileObjectId = Objects.requireNonNull(fileObjectId);
		this.ownerId = Objects.requireNonNull(ownerId);
		this.target = Objects.requireNonNull(target);
		this.visibility = Objects.requireNonNull(visibility);
	}

	public boolean canAccess(UUID actorId, boolean administrator, boolean participant) {
		return administrator || ownerId.equals(actorId) || visibility == Visibility.PUBLIC
				|| (visibility == Visibility.LOGIN_REQUIRED && actorId != null)
				|| (visibility == Visibility.PARTICIPANTS_ONLY && participant);
	}

	public void changeVisibility(UUID actorId, Visibility next) {
		if (!ownerId.equals(actorId))
			throw new DomainException("FILE_RELATION_FORBIDDEN", "只能由关系所有者修改");
		visibility = Objects.requireNonNull(next);
		changed();
	}

	public UUID publicId() {
		return publicId;
	}
	public UUID fileObjectId() {
		return fileObjectId;
	}
	public UUID ownerId() {
		return ownerId;
	}
	public TargetRef target() {
		return target;
	}
	public Visibility visibility() {
		return visibility;
	}
}
