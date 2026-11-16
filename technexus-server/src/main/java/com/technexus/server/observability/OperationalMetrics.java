package com.technexus.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OperationalMetrics {
	private static final Logger log = LoggerFactory.getLogger(OperationalMetrics.class);
	private final JdbcTemplate jdbc;
	private final Map<String, AtomicLong> queueDepths = new LinkedHashMap<>();
	private final AtomicLong databaseUp = new AtomicLong();
	private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();
	private final Counter collectionFailures;
	private final Timer databasePing;

	public OperationalMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		registerQueue(registry, "file_scan", "SCANNING");
		registerQueue(registry, "file_scan", "BLOCKED");
		registerQueue(registry, "file_upload", "CREATED");
		registerQueue(registry, "file_upload", "REUSED");
		registerQueue(registry, "file_upload", "CLEANING");
		registerQueue(registry, "ai_suggestion", "QUEUED");
		registerQueue(registry, "ai_suggestion", "RUNNING");
		registerQueue(registry, "ai_suggestion", "FAILED");
		registerQueue(registry, "batch_job", "RUNNING");
		registerQueue(registry, "batch_job", "FAILED");
		registerQueue(registry, "batch_job", "PARTIAL_FAILED");
		registerQueue(registry, "outbox", "NEW");
		registerQueue(registry, "outbox", "FAILED");
		Gauge.builder("technexus.database.up", databaseUp, AtomicLong::get).description("Database probe status")
				.register(registry);
		Gauge.builder("technexus.observability.last.success.epoch.seconds", lastSuccessEpochSeconds, AtomicLong::get)
				.description("Unix timestamp of the latest successful operational metrics collection")
				.register(registry);
		collectionFailures = Counter.builder("technexus.observability.collection.failures")
				.description("Operational metrics collection failures").register(registry);
		databasePing = Timer.builder("technexus.database.ping").description("Database SELECT 1 latency")
				.register(registry);
	}

	@Scheduled(fixedDelayString = "${technexus.observability.poll-ms:15000}", initialDelayString = "${technexus.observability.initial-delay-ms:5000}")
	public void collect() {
		var started = System.nanoTime();
		try {
			jdbc.queryForObject("SELECT 1", Integer.class);
			databasePing.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
			databaseUp.set(1);
			set("file_scan", "SCANNING", count("tn_file_object", "state", "SCANNING"));
			set("file_scan", "BLOCKED", count("tn_file_object", "state", "BLOCKED"));
			for (var state : new String[]{"CREATED", "REUSED", "CLEANING"})
				set("file_upload", state, count("tn_file_upload_session", "state", state));
			for (var state : new String[]{"QUEUED", "RUNNING", "FAILED"})
				set("ai_suggestion", state, count("tn_ai_suggestion", "state", state));
			for (var state : new String[]{"RUNNING", "FAILED", "PARTIAL_FAILED"})
				set("batch_job", state, count("tn_batch_job", "status", state));
			for (var state : new String[]{"NEW", "FAILED"})
				set("outbox", state, count("tn_outbox_event", "status", state));
			lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
		} catch (RuntimeException error) {
			databaseUp.set(0);
			collectionFailures.increment();
			log.warn("operational_metrics_collection_failed errorType={}", error.getClass().getSimpleName());
		}
	}

	private void registerQueue(MeterRegistry registry, String queue, String state) {
		var value = new AtomicLong();
		queueDepths.put(key(queue, state), value);
		Gauge.builder("technexus.queue.depth", value, AtomicLong::get).tag("queue", queue).tag("state", state)
				.description("Current durable queue depth by bounded queue and state").register(registry);
	}

	private long count(String table, String column, String state) {
		var value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?", Long.class, state);
		return value == null ? 0 : value;
	}

	private void set(String queue, String state, long value) {
		queueDepths.get(key(queue, state)).set(value);
	}

	private static String key(String queue, String state) {
		return queue + ':' + state;
	}
}
