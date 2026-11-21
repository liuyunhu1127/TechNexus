package com.technexus.server.admin.application;

import com.technexus.common.domain.DomainException;
import com.technexus.content.api.PostRepository;
import com.technexus.content.domain.ContentState;
import com.technexus.content.domain.Post;
import com.technexus.server.admin.application.port.OperationsRepository;
import com.technexus.server.auth.application.port.AuthAccountStore;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ManagementApplicationService {
	private static final int PAGE_SIZE = 20;
	private final UserRepository users;
	private final PostRepository posts;
	private final AuthAccountStore accounts;
	private final OperationsRepository operations;

	public ManagementApplicationService(UserRepository users, PostRepository posts, AuthAccountStore accounts,
			OperationsRepository operations) {
		this.users = users;
		this.posts = posts;
		this.accounts = accounts;
		this.operations = operations;
	}

	@Transactional(readOnly = true)
	public Page<ManagedUser> listUsers(String query, UserStatus status, int page) {
		var result = users.listForManagement(searchTerm(query), status, (page - 1) * PAGE_SIZE, PAGE_SIZE + 1);
		return page(result.stream().map(ManagementApplicationService::userView).toList(), page);
	}

	public ManagedUser changeUserStatus(UUID actor, UUID id, boolean enabled, long expectedVersion, String reason) {
		if (actor.equals(id))
			throw new DomainException("ADMIN_SELF_ACTION_FORBIDDEN", "不能修改当前管理员账号的状态");
		var user = users.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "用户不存在"));
		requireVersion(user.version(), expectedVersion);
		var previous = user.status();
		if (enabled) {
			user.enable();
		} else {
			if (previous != UserStatus.ACTIVE)
				throw new DomainException("USER_TRANSITION_INVALID", "只能停用正常账号");
			user.disable();
		}
		users.save(user);
		if (!enabled)
			accounts.revokeAllSessions(id);
		audit(actor, id, enabled ? "USER_ENABLE" : "USER_DISABLE", previous, user.status(), user.version(), reason);
		return userView(user);
	}

	@Transactional(readOnly = true)
	public Page<PostRepository.ManagementEntry> listPosts(String query, ContentState state, int page) {
		var result = posts.listForManagement(searchTerm(query), state, (page - 1) * PAGE_SIZE, PAGE_SIZE + 1);
		return page(result, page);
	}

	@Transactional(readOnly = true)
	public ManagedPost getPost(UUID id) {
		return postView(requirePost(id));
	}

	public ManagedPost changePostState(UUID actor, UUID id, boolean published, long expectedVersion, String reason) {
		var post = requirePost(id);
		requireVersion(post.version(), expectedVersion);
		var previous = post.state();
		if (published)
			post.restorePublication();
		else
			post.takeOffline();
		posts.save(post);
		audit(actor, id, published ? "POST_RESTORE" : "POST_OFFLINE", previous, post.state(), post.version(), reason);
		return postView(post);
	}

	private Post requirePost(UUID id) {
		return posts.findById(id).orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "帖子不存在"));
	}

	private ManagedPost postView(Post post) {
		var latest = post.versions().getLast();
		var published = post.versions().stream()
				.filter(version -> Objects.equals(version.publicId(), post.publishedVersionId())).findFirst()
				.orElse(null);
		var owner = users.findById(post.ownerId()).map(User::displayName).orElse("");
		return new ManagedPost(post.publicId(), post.ownerId(), owner, post.type().name(), post.state(),
				post.visibility().name(), post.version(), latest.title(), latest.body(),
				published == null ? null : published.title(), published == null ? null : published.body());
	}

	private void audit(UUID actor, UUID id, String action, Enum<?> before, Enum<?> after, long version, String reason) {
		operations.audit(actor, action, Map.of("targetId", id.toString(), "before", before.name(), "after",
				after.name(), "version", version, "reason", reason.strip()));
	}

	private static void requireVersion(long actual, long expected) {
		if (actual != expected)
			throw new DomainException("VERSION_CONFLICT", "记录已变化，请刷新后重试");
	}

	private static String searchTerm(String value) {
		return value.strip().toLowerCase(Locale.ROOT);
	}

	private static <T> Page<T> page(List<T> results, int number) {
		return new Page<>(results.stream().limit(PAGE_SIZE).toList(), number, results.size() > PAGE_SIZE);
	}

	private static ManagedUser userView(User user) {
		return new ManagedUser(user.publicId(), user.normalizedEmail(), user.displayName(), user.status(),
				user.version());
	}

	public record Page<T>(List<T> items, int page, boolean hasNext) {
	}
	public record ManagedUser(UUID id, String email, String displayName, UserStatus status, long version) {
	}
	public record ManagedPost(UUID id, UUID ownerId, String ownerName, String type, ContentState state,
			String visibility, long version, String title, String body, String publishedTitle, String publishedBody) {
	}
}
