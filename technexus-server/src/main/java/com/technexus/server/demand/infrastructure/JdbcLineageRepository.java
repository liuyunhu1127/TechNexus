package com.technexus.server.demand.infrastructure;

import com.technexus.common.domain.TargetRef;
import com.technexus.demand.api.LineageRepository;
import com.technexus.demand.domain.Lineage;
import com.technexus.demand.domain.LineageType;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
public class JdbcLineageRepository implements LineageRepository {
	private final JdbcTemplate jdbc;
	public JdbcLineageRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void add(Lineage lineage) {
		jdbc.update(
				"""
						INSERT INTO tn_demand_lineage(public_id,source_type,source_public_id,target_type,target_public_id,lineage_type,version,created_at,updated_at)
						VALUES (?,?,?,?,?,?,0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
						""",
				bytes(lineage.publicId()), lineage.source().type(), bytes(lineage.source().publicId()),
				lineage.target().type(), bytes(lineage.target().publicId()), lineage.type().name());
	}

	@Override
	public List<Lineage> findConnected(TargetRef target) {
		return jdbc.query("""
				SELECT * FROM tn_demand_lineage
				WHERE (source_type=? AND source_public_id=?) OR (target_type=? AND target_public_id=?)
				ORDER BY created_at,public_id
				""",
				(result, row) -> new Lineage(uuid(result.getBytes("public_id")),
						new TargetRef(result.getString("source_type"), uuid(result.getBytes("source_public_id"))),
						new TargetRef(result.getString("target_type"), uuid(result.getBytes("target_public_id"))),
						LineageType.valueOf(result.getString("lineage_type"))),
				target.type(), bytes(target.publicId()), target.type(), bytes(target.publicId()));
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
