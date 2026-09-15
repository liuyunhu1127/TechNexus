package com.technexus.demand.domain;

import com.technexus.common.domain.AggregateRoot;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.util.Objects;
import java.util.UUID;

public final class Lineage extends AggregateRoot {
	private final UUID publicId;
	private final TargetRef source;
	private final TargetRef target;
	private final LineageType type;

	public Lineage(UUID publicId, TargetRef source, TargetRef target, LineageType type) {
		this.publicId = Objects.requireNonNull(publicId);
		this.source = Objects.requireNonNull(source);
		this.target = Objects.requireNonNull(target);
		this.type = Objects.requireNonNull(type);
		if (source.equals(target))
			throw new DomainException("LINEAGE_SELF_LOOP", "来源关系不能自环");
	}

	public UUID publicId() {
		return publicId;
	}
	public TargetRef source() {
		return source;
	}
	public TargetRef target() {
		return target;
	}
	public LineageType type() {
		return type;
	}
}
