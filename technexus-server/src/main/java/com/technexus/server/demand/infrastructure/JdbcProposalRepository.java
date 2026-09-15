package com.technexus.server.demand.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.demand.api.ProposalRepository;
import com.technexus.demand.domain.Proposal;
import com.technexus.demand.domain.ProposalState;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcProposalRepository implements ProposalRepository {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public JdbcProposalRepository(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public Optional<Proposal> findById(UUID publicId) {
		return jdbc.query(select() + " WHERE p.public_id=?", this::map, bytes(publicId)).stream().findFirst();
	}

	@Override
	public void add(Proposal proposal) {
		var changed = jdbc.update(
				"""
						INSERT INTO tn_demand_proposal(public_id,demand_id,provider_id,plan,tech_stack,estimated_days,quote,state,version,created_at,updated_at)
						SELECT ?,d.id,u.id,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
						FROM tn_demand d JOIN tn_user u ON u.public_id=? WHERE d.public_id=?
						""",
				bytes(proposal.publicId()), proposal.plan(), writeJson(proposal.techStack()), proposal.estimatedDays(),
				proposal.quote().amount(), proposal.state().name(), proposal.version(), bytes(proposal.providerId()),
				bytes(proposal.demandId()));
		if (changed != 1)
			throw new DomainException("RESOURCE_NOT_FOUND", "需求或服务者不存在");
	}

	@Override
	public void save(Proposal proposal) {
		var changed = jdbc.update(
				"UPDATE tn_demand_proposal SET state=?,version=?,updated_at=CURRENT_TIMESTAMP(6) WHERE public_id=? AND version=?",
				proposal.state().name(), proposal.version(), bytes(proposal.publicId()), proposal.version() - 1);
		if (changed != 1)
			throw new DomainException("VERSION_CONFLICT", "方案版本已变化");
	}

	@Override
	public boolean isAcceptedProvider(UUID demandId, UUID providerId) {
		var count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM tn_demand_proposal p
				JOIN tn_demand d ON d.id=p.demand_id JOIN tn_user u ON u.id=p.provider_id
				WHERE d.public_id=? AND u.public_id=? AND p.state='ACCEPTED'
				""", Integer.class, bytes(demandId), bytes(providerId));
		return count != null && count > 0;
	}

	private Proposal map(ResultSet result, int row) throws SQLException {
		return Proposal.restore(uuid(result.getBytes("public_id")), uuid(result.getBytes("demand_public_id")),
				uuid(result.getBytes("provider_public_id")), result.getString("plan"),
				readJson(result.getString("tech_stack")), result.getInt("estimated_days"),
				Money.cny(result.getBigDecimal("quote")), ProposalState.valueOf(result.getString("state")),
				result.getLong("version"));
	}

	private List<String> readJson(String value) {
		try {
			return json.readValue(value, new TypeReference<>() {
			});
		} catch (JsonProcessingException error) {
			throw new DomainException("PROPOSAL_DATA_INVALID", "方案数据损坏");
		}
	}

	private String writeJson(List<String> value) {
		try {
			return json.writeValueAsString(value);
		} catch (JsonProcessingException error) {
			throw new DomainException("PROPOSAL_DATA_INVALID", "方案数据无法序列化");
		}
	}

	private static String select() {
		return "SELECT p.*,d.public_id demand_public_id,u.public_id provider_public_id FROM tn_demand_proposal p JOIN tn_demand d ON d.id=p.demand_id JOIN tn_user u ON u.id=p.provider_id";
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
