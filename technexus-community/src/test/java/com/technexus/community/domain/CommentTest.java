package com.technexus.community.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommentTest {
	@Test
	void deletionKeepsTombstoneIdentityAndParent() {
		var author = UUID.randomUUID();
		var parent = UUID.randomUUID();
		var comment = new Comment(UUID.randomUUID(), author, new TargetRef("ARTICLE", UUID.randomUUID()), parent,
				"useful");
		comment.delete(author);
		assertEquals(CommentState.DELETED, comment.state());
		assertEquals("", comment.body());
		assertEquals(parent, comment.parentId());
	}

	@Test
	void nonOwnerCannotEdit() {
		var comment = new Comment(UUID.randomUUID(), UUID.randomUUID(), new TargetRef("POST", UUID.randomUUID()), null,
				"body");
		assertThrows(DomainException.class, () -> comment.edit(UUID.randomUUID(), "changed"));
	}

	@Test
	void editBlockAndValidationRulesAreEnforced() {
		var owner = UUID.randomUUID();
		var comment = new Comment(UUID.randomUUID(), owner, new TargetRef("POST", UUID.randomUUID()), null, " body ");
		assertEquals("body", comment.body());
		comment.edit(owner, " changed ");
		assertEquals("changed", comment.body());
		comment.block();
		assertEquals(CommentState.BLOCKED, comment.state());
		assertThrows(DomainException.class, () -> comment.edit(owner, "again"));
		assertThrows(DomainException.class,
				() -> new Comment(UUID.randomUUID(), owner, new TargetRef("POST", UUID.randomUUID()), null, " "));
		assertThrows(DomainException.class, () -> new Comment(UUID.randomUUID(), owner,
				new TargetRef("POST", UUID.randomUUID()), null, "x".repeat(5001)));
	}
}
