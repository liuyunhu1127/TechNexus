package com.technexus.server.community.infrastructure;

import com.technexus.community.api.NotificationRepository;
import com.technexus.community.api.NotificationView;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcNotificationRepository implements NotificationRepository {
	private final JdbcTemplate jdbc;
	public JdbcNotificationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}
	@Override
	public List<NotificationView> listFor(UUID recipientId, int limit) {
		return jdbc.query("""
				SELECT n.public_id,u.public_id recipient_public_id,n.notification_type,n.message,n.created_at,n.read_at
				FROM tn_notification n JOIN tn_user u ON u.id=n.recipient_id WHERE u.public_id=?
				ORDER BY n.created_at DESC,n.id DESC LIMIT ?
				""",
				(result, row) -> new NotificationView(uuid(result.getBytes("public_id")),
						uuid(result.getBytes("recipient_public_id")), result.getString("notification_type"),
						result.getString("message"), result.getTimestamp("created_at").toInstant(),
						result.getTimestamp("read_at") == null ? null : result.getTimestamp("read_at").toInstant()),
				bytes(recipientId), Math.min(Math.max(limit, 1), 100));
	}
	@Override
	public boolean markRead(UUID recipientId, UUID notificationId, Instant readAt) {
		return jdbc.update("""
				UPDATE tn_notification n JOIN tn_user u ON u.id=n.recipient_id
				SET n.read_at=COALESCE(n.read_at,?),n.updated_at=CURRENT_TIMESTAMP(6)
				WHERE n.public_id=? AND u.public_id=?
				""", Timestamp.from(readAt), bytes(notificationId), bytes(recipientId)) == 1;
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
