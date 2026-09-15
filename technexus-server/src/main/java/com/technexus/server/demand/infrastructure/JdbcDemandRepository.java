package com.technexus.server.demand.infrastructure;

import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.Visibility;
import com.technexus.demand.api.DemandRepository;
import com.technexus.demand.domain.BudgetRange;
import com.technexus.demand.domain.Demand;
import com.technexus.demand.domain.DemandState;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcDemandRepository implements DemandRepository {
	private final JdbcTemplate jdbc;
	public JdbcDemandRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Demand> findById(UUID id) {
		return jdbc.query(select() + " WHERE d.public_id=?", this::map, bytes(id)).stream().findFirst();
	}

	@Override
	public List<Demand> listPublic(int limit) {
		return jdbc.query(select()
				+ " WHERE d.visibility='PUBLIC' AND d.state IN ('PUBLISHED','IN_PROGRESS','COMPLETED') ORDER BY d.updated_at DESC LIMIT ?",
				this::map, Math.min(Math.max(limit, 1), 100));
	}

	@Override
	public void add(Demand demand) {
		var changed = jdbc.update(
				"""
						INSERT INTO tn_demand(public_id,owner_id,state,title,description,budget_min,budget_max,deadline_at,visibility,version,created_at,updated_at)
						SELECT ?,id,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) FROM tn_user WHERE public_id=?
						""",
				bytes(demand.publicId()), demand.state().name(), demand.title(), demand.description(),
				amount(demand, true), amount(demand, false), timestamp(demand.deadlineAt()), demand.visibility().name(),
				demand.version(), bytes(demand.ownerId()));
		if (changed != 1)
			throw new DomainException("AUTH_REQUIRED", "需求所有者不存在");
		jdbc.update("""
				INSERT INTO tn_demand_state_history(demand_id,from_state,to_state,actor_id,reason,created_at,updated_at)
				SELECT d.id,NULL,?,u.id,'CREATE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
				FROM tn_demand d JOIN tn_user u ON u.public_id=? WHERE d.public_id=?
				""", demand.state().name(), bytes(demand.ownerId()), bytes(demand.publicId()));
	}

	@Override
	public void save(Demand demand, UUID actorId, String reason) {
		var previousState = jdbc.queryForObject("SELECT state FROM tn_demand WHERE public_id=?", String.class,
				bytes(demand.publicId()));
		var changed = jdbc.update("""
				UPDATE tn_demand SET state=?,title=?,description=?,budget_min=?,budget_max=?,deadline_at=?,visibility=?,
				                     pending_review_version_id=?,version=?,updated_at=CURRENT_TIMESTAMP(6)
				WHERE public_id=? AND version=?
				""", demand.state().name(), demand.title(), demand.description(), amount(demand, true),
				amount(demand, false), timestamp(demand.deadlineAt()), demand.visibility().name(),
				nullableBytes(demand.pendingReviewVersionId()), demand.version(), bytes(demand.publicId()),
				demand.version() - 1);
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "需求版本已变化");
		if (!demand.state().name().equals(previousState)) {
			jdbc.update(
					"""
							INSERT INTO tn_demand_state_history(demand_id,from_state,to_state,actor_id,reason,created_at,updated_at)
							SELECT d.id,?,?,u.id,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
							FROM tn_demand d LEFT JOIN tn_user u ON u.public_id=? WHERE d.public_id=?
							""",
					previousState, demand.state().name(), reason, bytes(actorId), bytes(demand.publicId()));
		}
	}

	private Demand map(ResultSet result, int row) throws SQLException {
		var minimum = result.getBigDecimal("budget_min");
		var maximum = result.getBigDecimal("budget_max");
		var deadline = result.getTimestamp("deadline_at");
		return Demand.restore(uuid(result.getBytes("public_id")), uuid(result.getBytes("owner_public_id")),
				result.getString("title"), result.getString("description"),
				new BudgetRange(minimum == null ? null : Money.cny(minimum),
						maximum == null ? null : Money.cny(maximum)),
				deadline == null ? null : deadline.toInstant(), Visibility.valueOf(result.getString("visibility")),
				DemandState.valueOf(result.getString("state")),
				nullableUuid(result.getBytes("pending_review_version_id")), result.getLong("version"));
	}
	private static String select() {
		return "SELECT d.*,u.public_id owner_public_id FROM tn_demand d JOIN tn_user u ON u.id=d.owner_id";
	}
	private static Object amount(Demand demand, boolean minimum) {
		if (demand.budget() == null)
			return null;
		var money = minimum ? demand.budget().minimum() : demand.budget().maximum();
		return money == null ? null : money.amount();
	}
	private static Timestamp timestamp(java.time.Instant value) {
		return value == null ? null : Timestamp.from(value);
	}
	private static byte[] bytes(UUID value) {
		return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
				.array();
	}
	private static byte[] nullableBytes(UUID value) {
		return value == null ? null : bytes(value);
	}
	private static UUID nullableUuid(byte[] value) {
		return value == null ? null : uuid(value);
	}
	private static UUID uuid(byte[] value) {
		var buffer = ByteBuffer.wrap(value);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
