package com.technexus.server.user.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.user.api.UserRepository;
import com.technexus.user.domain.User;
import com.technexus.user.domain.UserStatus;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcUserRepository implements UserRepository {
	private final JdbcTemplate jdbc;
	public JdbcUserRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	@Override
	public Optional<User> findById(UUID id) {
		return jdbc.query(select() + " WHERE public_id=?", this::map, bytes(id)).stream().findFirst();
	}
	@Override
	public Optional<User> findByEmail(String email) {
		return jdbc.query(select() + " WHERE email_normalized=?", this::map, email).stream().findFirst();
	}

	@Override
	public List<User> listForManagement(String query, UserStatus status, int offset, int limit) {
		var state = status == null ? "" : status.name();
		return jdbc.query(select() + """
				 WHERE (?='' OR status=?)
				   AND (LOCATE(?, LOWER(display_name))>0 OR LOCATE(?, email_normalized)>0)
				 ORDER BY id DESC LIMIT ? OFFSET ?
				""", this::map, state, state, query, query, limit, offset);
	}
	@Override
	public void save(User user) {
		final int changed;
		try {
			changed = jdbc.update("""
					UPDATE tn_user SET display_name=?,bio=?,status=?,session_version=?,version=?,
					  email_normalized=?,email_verified=?,avatar_object_key=?,updated_at=CURRENT_TIMESTAMP(6)
					WHERE public_id=? AND version=?
					""", user.displayName(), user.bio(), user.status().name(), user.sessionVersion(), user.version(),
					user.normalizedEmail(), user.emailVerified(), user.avatarObjectKey(), bytes(user.publicId()),
					user.version() - 1);
		} catch (DuplicateKeyException error) {
			throw new DomainException("EMAIL_UNAVAILABLE", "该邮箱无法绑定，请使用其他邮箱");
		}
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "用户资料版本已变化");
	}
	private User map(ResultSet result, int row) throws SQLException {
		return User.restore(uuid(result.getBytes("public_id")), result.getString("email_normalized"),
				result.getString("display_name"), result.getString("bio"),
				UserStatus.valueOf(result.getString("status")), result.getLong("session_version"),
				result.getLong("version"), result.getBoolean("email_verified"), result.getString("avatar_object_key"));
	}
	private static String select() {
		return "SELECT public_id,email_normalized,display_name,bio,status,session_version,version,email_verified,avatar_object_key FROM tn_user";
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
