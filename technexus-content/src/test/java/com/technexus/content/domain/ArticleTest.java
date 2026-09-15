package com.technexus.content.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArticleTest {
	@Test
	void pendingRevisionDoesNotReplacePublishedVersion() {
		var owner = UUID.randomUUID();
		var first = new ArticleVersion(UUID.randomUUID(), 1, "First", "Summary", "Body");
		var article = new Article(UUID.randomUUID(), owner, Visibility.PUBLIC, first);
		article.submit(owner);
		article.applyReview(first.publicId(), true);
		var published = article.publishedVersionId();

		var second = article.revise(owner, UUID.randomUUID(), "Second", "Summary", "Changed");
		article.submit(owner);
		assertEquals(ContentState.PENDING_REVIEW, article.state());
		assertEquals(published, article.publishedVersionId());
		assertNotEquals(published, second.publicId());
	}

	@Test
	void staleReviewCannotChangeContent() {
		var owner = UUID.randomUUID();
		var first = new ArticleVersion(UUID.randomUUID(), 1, "First", "Summary", "Body");
		var article = new Article(UUID.randomUUID(), owner, Visibility.PUBLIC, first);
		article.submit(owner);
		assertThrows(DomainException.class, () -> article.applyReview(UUID.randomUUID(), true));
	}

	@Test
	void rejectionAndRevisionRespectOwnershipAndPendingReview() {
		var owner = UUID.randomUUID();
		var first = new ArticleVersion(UUID.randomUUID(), 1, " First ", null, " Body ");
		var article = new Article(UUID.randomUUID(), owner, Visibility.LOGIN_REQUIRED, first);
		assertEquals("", first.summary());
		assertThrows(DomainException.class, () -> article.revise(UUID.randomUUID(), UUID.randomUUID(), "x", "y", "z"));
		article.submit(owner);
		assertThrows(DomainException.class,
				() -> article.revise(owner, UUID.randomUUID(), "second", "summary", "body"));
		article.applyReview(first.publicId(), false);
		assertEquals(ContentState.REJECTED, article.state());
		assertNull(article.pendingVersionId());
		var second = article.revise(owner, UUID.randomUUID(), "second", "summary", "body", Visibility.PUBLIC);
		article.submit(owner);
		article.applyReview(second.publicId(), true);
		article.revise(owner, UUID.randomUUID(), "third", "summary", "body");
		var third = article.submit(owner);
		article.applyReview(third, false);
		assertEquals(ContentState.PUBLISHED, article.state());
	}

	@Test
	void validatesVersionsAndRestore() {
		assertThrows(DomainException.class, () -> new ArticleVersion(UUID.randomUUID(), 0, "t", "s", "b"));
		assertThrows(DomainException.class, () -> new ArticleVersion(UUID.randomUUID(), 1, " ", "s", "b"));
		assertThrows(DomainException.class, () -> new ArticleVersion(UUID.randomUUID(), 1, "t", "x".repeat(501), "b"));
		assertThrows(DomainException.class,
				() -> new ArticleVersion(UUID.randomUUID(), 1, "t", "s", "x".repeat(200001)));
		assertThrows(DomainException.class, () -> Article.restore(UUID.randomUUID(), UUID.randomUUID(),
				Visibility.PUBLIC, java.util.List.of(), ContentState.DRAFT, null, null, 0));
		var version = ArticleVersion.restore(UUID.randomUUID(), 1, "t", "s", "b", VersionState.APPROVED);
		var article = Article.restore(UUID.randomUUID(), UUID.randomUUID(), Visibility.OWNER_ONLY,
				java.util.List.of(version), ContentState.PUBLISHED, null, version.publicId(), 6);
		assertEquals(6, article.version());
		assertEquals(VersionState.APPROVED, article.versions().getFirst().state());
	}
}
