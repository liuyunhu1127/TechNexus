package com.technexus.server.community.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.community.api.TagRepository;
import com.technexus.community.domain.Tag;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("test")
public class InMemoryTagRepository implements TagRepository {
	private final ConcurrentHashMap<UUID, Tag> values = new ConcurrentHashMap<>();

	@Override
	public List<Tag> list(int limit) {
		return values.values().stream().map(InMemoryTagRepository::copy)
				.sorted(Comparator.comparing(Tag::normalizedName)).limit(limit).toList();
	}

	@Override
	public Optional<Tag> findById(UUID id) {
		return Optional.ofNullable(values.get(id)).map(InMemoryTagRepository::copy);
	}

	@Override
	public void add(Tag tag, UUID actorId, String reason) {
		requireNamesAvailable(tag, null, null);
		if (values.putIfAbsent(tag.publicId(), copy(tag)) != null)
			throw new DomainException("TAG_EXISTS", "标签已存在");
	}

	@Override
	public void save(Tag tag, long expectedVersion, UUID actorId, String reason) {
		requireNamesAvailable(tag, tag.publicId(), null);
		values.compute(tag.publicId(), (id, current) -> {
			if (current == null)
				throw new DomainException("RESOURCE_NOT_FOUND", "标签不存在");
			if (current.version() != expectedVersion)
				throw new DomainException("VERSION_CONFLICT", "标签版本已变化");
			return copy(tag);
		});
	}

	@Override
	public void merge(Tag source, Tag target, long expectedSourceVersion, long expectedTargetVersion, UUID actorId,
			String reason) {
		var currentSource = values.get(source.publicId());
		var currentTarget = values.get(target.publicId());
		if (currentSource == null || currentTarget == null)
			throw new DomainException("RESOURCE_NOT_FOUND", "标签不存在");
		if (currentSource.version() != expectedSourceVersion || currentTarget.version() != expectedTargetVersion)
			throw new DomainException("VERSION_CONFLICT", "标签版本已变化");
		requireNamesAvailable(target, source.publicId(), target.publicId());
		values.put(source.publicId(), copy(source));
		values.put(target.publicId(), copy(target));
	}

	private void requireNamesAvailable(Tag candidate, UUID allowedOne, UUID allowedTwo) {
		for (var tag : values.values()) {
			if (tag.publicId().equals(allowedOne) || tag.publicId().equals(allowedTwo))
				continue;
			if (tag.normalizedName().equals(candidate.normalizedName())
					|| tag.aliases().contains(candidate.normalizedName())
					|| candidate.aliases().contains(tag.normalizedName())
					|| candidate.aliases().stream().anyMatch(tag.aliases()::contains))
				throw new DomainException("TAG_NAME_CONFLICT", "标签标准名或别名已存在");
		}
	}

	private static Tag copy(Tag value) {
		return Tag.restore(value.publicId(), value.normalizedName(), value.enabled(), value.aliases(),
				value.mergedInto(), value.version());
	}
}
