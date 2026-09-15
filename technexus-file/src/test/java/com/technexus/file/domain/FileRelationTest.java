package com.technexus.file.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.Visibility;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileRelationTest {
	@Test
	void evaluatesEveryAccessPolicy() {
		var owner = UUID.randomUUID();
		var actor = UUID.randomUUID();
		var target = new TargetRef("ARTICLE", UUID.randomUUID());
		var relation = new FileRelation(UUID.randomUUID(), UUID.randomUUID(), owner, target,
				Visibility.PARTICIPANTS_ONLY);
		assertTrue(relation.canAccess(actor, true, false));
		assertTrue(relation.canAccess(owner, false, false));
		assertTrue(relation.canAccess(actor, false, true));
		assertFalse(relation.canAccess(actor, false, false));
		relation.changeVisibility(owner, Visibility.PUBLIC);
		assertTrue(relation.canAccess(null, false, false));
		relation.changeVisibility(owner, Visibility.LOGIN_REQUIRED);
		assertTrue(relation.canAccess(actor, false, false));
		assertFalse(relation.canAccess(null, false, false));
		assertThrows(DomainException.class, () -> relation.changeVisibility(actor, Visibility.PUBLIC));
	}
}
