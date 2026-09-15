package com.technexus.content.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Visibility;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostTest {
	@Test
	void supportsApproveRejectAndPublishedFallback() {
		var owner = UUID.randomUUID();
		var first = new PostVersion(UUID.randomUUID(), 1, "first", "body");
		var post = new Post(UUID.randomUUID(), owner, PostType.POST, Visibility.PUBLIC, first);
		assertThrows(DomainException.class, () -> post.submit(UUID.randomUUID()));
		post.submit(owner);
		assertThrows(DomainException.class,
				() -> post.revise(owner, UUID.randomUUID(), "second", "body", Visibility.OWNER_ONLY));
		post.applyReview(first.publicId(), false);
		assertEquals(ContentState.REJECTED, post.state());
		assertNull(post.pendingVersionId());
		var second = post.revise(owner, UUID.randomUUID(), "second", "body", Visibility.OWNER_ONLY);
		post.submit(owner);
		post.applyReview(second.publicId(), true);
		var third = post.revise(owner, UUID.randomUUID(), "third", "body", Visibility.LOGIN_REQUIRED);
		post.submit(owner);
		post.applyReview(third.publicId(), false);
		assertEquals(ContentState.PUBLISHED, post.state());
		assertEquals(second.publicId(), post.publishedVersionId());
	}

	@Test
	void rejectsStaleReviewAndInvalidVersions() {
		var owner = UUID.randomUUID();
		var version = new PostVersion(UUID.randomUUID(), 1, "title", "body");
		var post = new Post(UUID.randomUUID(), owner, PostType.PROBLEM, Visibility.PUBLIC, version);
		post.submit(owner);
		assertThrows(DomainException.class, () -> post.applyReview(UUID.randomUUID(), true));
		assertThrows(IllegalArgumentException.class, () -> new PostVersion(UUID.randomUUID(), 0, "title", "body"));
		assertThrows(DomainException.class, () -> new PostVersion(UUID.randomUUID(), 1, " ", "body"));
		assertThrows(DomainException.class, () -> Post.restore(UUID.randomUUID(), owner, PostType.SOLUTION,
				Visibility.PUBLIC, List.of(), ContentState.DRAFT, null, null, 0));
	}

	@Test
	void restoresApprovedPost() {
		var version = PostVersion.restore(UUID.randomUUID(), 1, "title", "body", VersionState.APPROVED);
		var post = Post.restore(UUID.randomUUID(), UUID.randomUUID(), PostType.SOLUTION, Visibility.PUBLIC,
				List.of(version), ContentState.PUBLISHED, null, version.publicId(), 5);
		assertEquals(5, post.version());
		assertEquals(PostType.SOLUTION, post.type());
		assertEquals(VersionState.APPROVED, post.versions().getFirst().state());
	}
}
