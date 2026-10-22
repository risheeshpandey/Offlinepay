package com.risheesh.notifications;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

@Component @RequiredArgsConstructor
@ConditionalOnProperty(name = "offlinepay.monitoring-enabled", havingValue = "true")
public class NotificationMetrics {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();
    @jakarta.annotation.PostConstruct void initialize() {
        Gauge.builder("offlinepay.notification.delivery.failed", failed, AtomicLong::get).register(meters);
        Gauge.builder("offlinepay.dead.letters.open", deadLetters, AtomicLong::get).register(meters);
    }
    @Scheduled(initialDelay = 15000, fixedDelay = 15000)
    public void refresh() {
        try {
            failed.set(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE delivery_status='FAILED'", Long.class));
            deadLetters.set(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_event WHERE status='OPEN'", Long.class));
        } catch (org.springframework.dao.DataAccessException ex) {
            meters.counter("offlinepay.monitoring.refresh.failures", "source", "notifications").increment();
        }
    }
}
