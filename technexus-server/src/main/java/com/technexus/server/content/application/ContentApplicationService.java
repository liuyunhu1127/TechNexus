package com.technexus.server.content.application;

import com.technexus.audit.api.AuditTaskRepository;
import com.technexus.audit.domain.AuditTask;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.TargetRef;
import com.technexus.common.domain.UuidV7;
import com.technexus.common.domain.Visibility;
import com.technexus.content.api.ArticleRepository;
import com.technexus.content.api.PostRepository;
import com.technexus.content.domain.Article;
import com.technexus.content.domain.ArticleVersion;
import com.technexus.content.domain.ContentState;
import com.technexus.content.domain.Post;
import com.technexus.content.domain.PostType;
import com.technexus.content.domain.PostVersion;
import com.technexus.server.content.application.port.ContentAssociationPort;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ContentApplicationService {
	private final ArticleRepository articles;
	private final PostRepository posts;
	private final AuditTaskRepository audits;
	private final ContentAssociationPort associations;

	public ContentApplicationService(ArticleRepository articles, PostRepository posts, AuditTaskRepository audits,
			ContentAssociationPort associations) {
		this.articles = articles;
		this.posts = posts;
		this.audits = audits;
		this.associations = associations;
	}

	public ContentView create(UUID ownerId, ContentCommand command) {
		var id = UuidV7.generate();
		var visibility = visibility(command.visibility());
		if ("DOCUMENT".equals(command.type())) {
			var article = new Article(id, ownerId, visibility,
					new ArticleVersion(UuidV7.generate(), 1, command.title(), command.summary(), command.body()));
			articles.add(article);
			associations.replace(id, ownerId, "DOCUMENT", empty(command.tagIds()), empty(command.fileIds()));
			return withAssociations(articleView(article, true));
		}
		var type = postType(command.type());
		var post = new Post(id, ownerId, type, visibility,
				new PostVersion(UuidV7.generate(), 1, command.title(), command.body()));
		posts.add(post);
		associations.replace(id, ownerId, type.name(), empty(command.tagIds()), empty(command.fileIds()));
		return withAssociations(postView(post, true));
	}

	@Transactional(readOnly = true)
	public List<ContentView> listPublic() {
		var result = new java.util.ArrayList<ContentView>();
		articles.listPublished(50).forEach(value -> result.add(withAssociations(articleView(value, false))));
		posts.listPublished(50).forEach(value -> result.add(withAssociations(postView(value, false))));
		return result.stream().limit(50).toList();
	}

	@Transactional(readOnly = true)
	public List<ContentView> searchPublic(String query) {
		var needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
		return listPublic().stream().filter(value -> value.title().toLowerCase(java.util.Locale.ROOT).contains(needle)
				|| value.summary().toLowerCase(java.util.Locale.ROOT).contains(needle)).toList();
	}

	@Transactional(readOnly = true)
	public ContentView get(UUID id, UUID actorId) {
		var article = articles.findById(id);
		if (article.isPresent()) {
			requireVisible(article.get().ownerId(), article.get().state(), article.get().visibility(), actorId);
			return withAssociations(articleView(article.get(), article.get().ownerId().equals(actorId)));
		}
		var post = posts.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "内容不存在"));
		requireVisible(post.ownerId(), post.state(), post.visibility(), actorId);
		return withAssociations(postView(post, post.ownerId().equals(actorId)));
	}

	public ContentView update(UUID id, UUID actorId, long expectedVersion, ContentCommand command) {
		var article = articles.findById(id);
		if (article.isPresent()) {
			var value = article.get();
			requireVersion(value.version(), expectedVersion);
			var current = value.versions().getLast();
			value.revise(actorId, UuidV7.generate(), fallback(command.title(), current.title()),
					command.summary() == null ? current.summary() : command.summary(),
					fallback(command.body(), current.body()),
					command.visibility() == null ? value.visibility() : visibility(command.visibility()));
			articles.save(value);
			associations.replace(id, actorId, "DOCUMENT", command.tagIds(), command.fileIds());
			return withAssociations(articleView(value, true));
		}
		var post = posts.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "内容不存在"));
		requireVersion(post.version(), expectedVersion);
		var current = post.versions().getLast();
		post.revise(actorId, UuidV7.generate(), fallback(command.title(), current.title()),
				fallback(command.body(), current.body()),
				command.visibility() == null ? post.visibility() : visibility(command.visibility()));
		posts.save(post);
		associations.replace(id, actorId, post.type().name(), command.tagIds(), command.fileIds());
		return withAssociations(postView(post, true));
	}

	public ContentView submit(UUID id, UUID actorId, long expectedVersion) {
		var article = articles.findById(id);
		if (article.isPresent()) {
			var value = article.get();
			requireVersion(value.version(), expectedVersion);
			requireOwner(value.ownerId(), actorId);
			var existing = existingAudit(value.publicId(), value.pendingVersionId());
			if (existing != null)
				return withAudit(withAssociations(articleView(value, true)), existing.publicId());
			var targetVersionId = value.submit(actorId);
			articles.save(value);
			return withAudit(withAssociations(articleView(value, true)),
					createAudit(value.publicId(), targetVersionId).publicId());
		}
		var post = posts.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "内容不存在"));
		requireVersion(post.version(), expectedVersion);
		requireOwner(post.ownerId(), actorId);
		var existing = existingAudit(post.publicId(), post.pendingVersionId());
		if (existing != null)
			return withAudit(withAssociations(postView(post, true)), existing.publicId());
		var targetVersionId = post.submit(actorId);
		posts.save(post);
		return withAudit(withAssociations(postView(post, true)),
				createAudit(post.publicId(), targetVersionId).publicId());
	}

	@Transactional(readOnly = true)
	public List<ContentAssociationPort.TagOption> listTags() {
		return associations.listTags();
	}

	private AuditTask createAudit(UUID contentId, UUID targetVersionId) {
		var task = new AuditTask(UuidV7.generate(), new TargetRef("CONTENT", contentId), targetVersionId);
		task.submit();
		audits.add(task);
		return task;
	}

	private AuditTask existingAudit(UUID contentId, UUID targetVersionId) {
		if (targetVersionId == null)
			return null;
		return audits.findByTarget(new TargetRef("CONTENT", contentId), targetVersionId).orElse(null);
	}

	private static ContentView articleView(Article article, boolean owner) {
		var version = selectArticleVersion(article, owner);
		return new ContentView(article.publicId(), "DOCUMENT", article.state().name(), article.visibility().name(),
				version.title(), version.summary(), version.body(), article.version(), null, List.of(), List.of());
	}
	private static ContentView postView(Post post, boolean owner) {
		var version = selectPostVersion(post, owner);
		return new ContentView(post.publicId(), post.type().name(), post.state().name(), post.visibility().name(),
				version.title(), "", version.body(), post.version(), null, List.of(), List.of());
	}
	private ContentView withAssociations(ContentView value) {
		var linked = associations.get(value.id(), value.type());
		return new ContentView(value.id(), value.type(), value.state(), value.visibility(), value.title(),
				value.summary(), value.body(), value.version(), value.auditTaskId(), linked.tagIds(), linked.fileIds());
	}
	private static ContentView withAudit(ContentView value, UUID auditTaskId) {
		return new ContentView(value.id(), value.type(), value.state(), value.visibility(), value.title(),
				value.summary(), value.body(), value.version(), auditTaskId, value.tagIds(), value.fileIds());
	}
	private static ArticleVersion selectArticleVersion(Article article, boolean owner) {
		if (!owner && article.publishedVersionId() != null)
			return article.versions().stream().filter(value -> value.publicId().equals(article.publishedVersionId()))
					.findFirst().orElseThrow();
		return article.versions().getLast();
	}
	private static PostVersion selectPostVersion(Post post, boolean owner) {
		if (!owner && post.publishedVersionId() != null)
			return post.versions().stream().filter(value -> value.publicId().equals(post.publishedVersionId()))
					.findFirst().orElseThrow();
		return post.versions().getLast();
	}
	private static void requireVisible(UUID owner, ContentState state, Visibility visibility, UUID actor) {
		if (owner.equals(actor))
			return;
		if (state != ContentState.PUBLISHED || visibility != Visibility.PUBLIC) {
			throw new DomainException("RESOURCE_NOT_FOUND", "内容不存在");
		}
	}
	private static void requireVersion(long actual, long expected) {
		if (actual != expected)
			throw new DomainException("VERSION_CONFLICT", "内容版本已变化");
	}
	private static String fallback(String value, String current) {
		return value == null ? current : value;
	}
	private static PostType postType(String value) {
		try {
			return PostType.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException error) {
			throw new DomainException("CONTENT_TYPE_INVALID", "内容类型无效");
		}
	}
	private static Visibility visibility(String value) {
		try {
			return Visibility.valueOf(value);
		} catch (IllegalArgumentException | NullPointerException error) {
			throw new DomainException("CONTENT_VISIBILITY_INVALID", "内容可见性无效");
		}
	}

	private static List<UUID> empty(List<UUID> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	public record ContentCommand(String type, String visibility, String title, String summary, String body,
			List<UUID> tagIds, List<UUID> fileIds) {
		public ContentCommand(String type, String visibility, String title, String summary, String body) {
			this(type, visibility, title, summary, body, List.of(), List.of());
		}
	}
	public record ContentView(UUID id, String type, String state, String visibility, String title, String summary,
			String body, long version, UUID auditTaskId, List<UUID> tagIds, List<UUID> fileIds) {
	}
	private static void requireOwner(UUID ownerId, UUID actorId) {
		if (!ownerId.equals(actorId))
			throw new DomainException("CONTENT_FORBIDDEN", "只能由内容所有者修改");
	}
}
