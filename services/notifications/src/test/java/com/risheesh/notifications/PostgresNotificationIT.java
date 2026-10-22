package com.risheesh.notifications;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

// mvn -f services/pom.xml verify, with Docker running. Actual Flyway schema + PG unique-key contention.
@Testcontainers
class PostgresNotificationIT extends NotificationBoundaryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("offlinepay_notifications").withUsername("notification_owner").withPassword("test-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);
        r.add("spring.datasource.username",postgres::getUsername);
        r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.flyway.enabled",() -> true);
        r.add("spring.jpa.hibernate.ddl-auto",() -> "validate");
    }
}
