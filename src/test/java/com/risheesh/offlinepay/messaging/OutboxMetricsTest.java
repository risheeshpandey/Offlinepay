package com.risheesh.offlinepay.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OutboxMetricsTest {
    @Test void refreshExportsCountsAndMarksStaleValuesWhenDatabaseQueryFails() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:metrics" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE outbox_event (status VARCHAR(16))");
        jdbc.update("INSERT INTO outbox_event VALUES ('PENDING'), ('PENDING'), ('FAILED')");
        var registry = new SimpleMeterRegistry();
        var metrics = new OutboxMetrics(jdbc, registry);
        metrics.initialize(); metrics.refresh();
        assertEquals(2, registry.get("offlinepay.outbox.events").tag("status", "PENDING").gauge().value());
        assertEquals(0, registry.get("offlinepay.outbox.events").tag("status", "PROCESSING").gauge().value());
        jdbc.execute("DROP TABLE outbox_event"); metrics.refresh();
        assertEquals(1, registry.get("offlinepay.monitoring.refresh.failures").counter().count());
        assertEquals(2, registry.get("offlinepay.outbox.events").tag("status", "PENDING").gauge().value());
        registry.close();
    }
}
