package com.technexus.community.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TagTest {
	@Test
	void normalizesAndMergesOnce() {
		var source = new Tag(UUID.randomUUID(), " Java ");
		var target = new Tag(UUID.randomUUID(), "Spring");
		assertEquals("java", source.normalizedName());
		source.mergeInto(target);
		assertEquals(target.publicId(), source.mergedInto());
		assertEquals(1, source.version());
		assertThrows(DomainException.class, () -> target.mergeInto(source));
		assertThrows(DomainException.class, () -> source.mergeInto(source));
	}

	@Test
	void rejectsBlankAndLongNames() {
		assertThrows(DomainException.class, () -> new Tag(UUID.randomUUID(), " "));
		assertThrows(DomainException.class, () -> new Tag(UUID.randomUUID(), "x".repeat(49)));
	}

	@Test
	void normalizesAliasesAndSupportsDisableBeforeMerge() {
		var tag = new Tag(UUID.randomUUID(), "Spring", java.util.List.of(" Spring Framework "));
		assertEquals(java.util.Set.of("spring framework"), tag.aliases());
		tag.setEnabled(false);
		assertEquals(false, tag.enabled());
		assertThrows(DomainException.class, () -> tag.replaceAliases(java.util.List.of("spring", "spring boot")));
	}
}
