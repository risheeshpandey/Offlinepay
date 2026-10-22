package com.risheesh.offlinepay.messaging;

import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "offlinepay.monitoring-enabled", havingValue = "true")
public class OutboxMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();
    @PostConstruct void initialize() {
        for (String status : List.of("PENDING", "PROCESSING", "PUBLISHED", "FAILED")) {
            var count = new AtomicLong(); counts.put(status, count);
            Gauge.builder("offlinepay.outbox.events", count, AtomicLong::get).tag("status", status).register(meters);
        }
    }
    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    public void refresh() {
        try {
            Map<String, Long> snapshot = new HashMap<>();
            jdbc.query("SELECT status, COUNT(*) AS total FROM outbox_event GROUP BY status",
                    row -> { snapshot.put(row.getString("status"), row.getLong("total")); });
            counts.forEach((status, count) -> count.set(snapshot.getOrDefault(status, 0L)));
        } catch (org.springframework.dao.DataAccessException ex) {
            meters.counter("offlinepay.monitoring.refresh.failures", "source", "outbox").increment();
        }
    }
}
