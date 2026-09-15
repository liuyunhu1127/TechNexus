package com.technexus.server.admin.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.technexus.common.domain.DomainException;
import com.technexus.common.domain.Money;
import com.technexus.common.domain.TargetRef;
import com.technexus.pricing.api.PriceObjectRepository;
import com.technexus.pricing.domain.PriceObject;
import com.technexus.pricing.domain.PricingMode;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@Profile("!test")
public class JdbcPriceObjectRepository implements PriceObjectRepository {
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;
	private final TransactionTemplate transactions;

	public JdbcPriceObjectRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
		this.jdbc = jdbc;
		this.json = json;
		this.transactions = new TransactionTemplate(manager);
	}

	@Override
	public Optional<PriceObject> findByTarget(TargetRef target) {
		return jdbc.query("""
				SELECT public_id,target_type,target_public_id,mode,final_amount,currency,valid_from,version
				FROM tn_price_object WHERE target_type=? AND target_public_id=?
				""", this::restore, target.type(), bytes(target.publicId())).stream().findFirst();
	}

	@Override
	public void add(PriceObject price) {
		transactions.executeWithoutResult(status -> {
			var changed = jdbc.update("""
					INSERT INTO tn_price_object(public_id,target_type,target_public_id,mode,final_amount,currency,
					                            valid_from,version,created_at,updated_at)
					VALUES (?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
					""", bytes(price.publicId()), price.target().type(), bytes(price.target().publicId()),
					price.mode().name(), amount(price), currency(price), Timestamp.from(price.validFrom()),
					price.version());
			if (changed != 1)
				throw new DomainException("PRICE_CREATE_FAILED", "定价保存失败");
			insertLatestHistory(price);
		});
	}

	@Override
	public void save(PriceObject price, long expectedVersion) {
		transactions.executeWithoutResult(status -> {
			var changed = jdbc.update(
					"""
							UPDATE tn_price_object SET mode=?,final_amount=?,currency=?,valid_from=?,version=?,updated_at=CURRENT_TIMESTAMP(6)
							WHERE target_type=? AND target_public_id=? AND version=?
							""",
					price.mode().name(), amount(price), currency(price), Timestamp.from(price.validFrom()),
					price.version(), price.target().type(), bytes(price.target().publicId()), expectedVersion);
			if (changed != 1)
				throw new DomainException("VERSION_CONFLICT", "定价版本已变化");
			insertLatestHistory(price);
		});
	}

	@Override
	public long countPending() {
		return 0;
	}

	private void insertLatestHistory(PriceObject price) {
		var history = price.history().getLast();
		try {
			var before = history.before() == null
					? null
					: json.writeValueAsString(java.util.Map.of("amount", history.before().amount().toPlainString(),
							"currency", history.before().currency().getCurrencyCode()));
			var after = json.writeValueAsString(java.util.Map.of("mode", price.mode().name(), "amount",
					price.amount() == null ? "0.00" : price.amount().amount().toPlainString(), "currency",
					currency(price), "validFrom", price.validFrom().toString()));
			var changed = jdbc.update(
					"""
							INSERT INTO tn_price_history(price_object_id,before_json,after_json,operator_id,reason,created_at,updated_at)
							SELECT p.id,?,?,u.id,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
							FROM tn_price_object p JOIN tn_user u ON u.public_id=? WHERE p.public_id=?
							""",
					before, after, history.reason(), bytes(history.operatorId()), bytes(price.publicId()));
			if (changed != 1)
				throw new DomainException("PRICE_OPERATOR_NOT_FOUND", "定价操作人不存在");
		} catch (DomainException error) {
			throw error;
		} catch (Exception error) {
			throw new IllegalStateException("Could not serialize price history", error);
		}
	}

	private PriceObject restore(ResultSet result, int row) throws SQLException {
		var amount = result.getBigDecimal("final_amount");
		var money = amount == null ? null : new Money(amount, Currency.getInstance(result.getString("currency")));
		return PriceObject.restore(uuid(result.getBytes("public_id")),
				new TargetRef(result.getString("target_type"), uuid(result.getBytes("target_public_id"))),
				PricingMode.valueOf(result.getString("mode")), money, result.getTimestamp("valid_from").toInstant(),
				List.of(), result.getLong("version"));
	}

	private static Object amount(PriceObject value) {
		return value.amount() == null ? null : value.amount().amount();
	}
	private static String currency(PriceObject value) {
		return value.amount() == null ? "CNY" : value.amount().currency().getCurrencyCode();
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
