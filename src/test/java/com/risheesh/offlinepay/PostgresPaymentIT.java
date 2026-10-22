package com.risheesh.offlinepay;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses every behavioral test against real PostgreSQL, including concurrency. */
@Testcontainers
class PostgresPaymentIT extends PaymentFoundationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '3s'");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Test void journalRejectsMutation() {
        var user = user(); fund(user, "10");
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE ledger_entry SET amount_paise=1 WHERE wallet_id=?", user.walletId()));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM ledger_entry WHERE wallet_id=?", user.walletId()));
    }
    @Test void databaseRejectsAnUnbalancedOperationAtCommit() {
        var user = user();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO payment_operation(id,actor_id,idempotency_key,request_hash,kind,receiver_wallet_id,amount_paise,created_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                UUID.randomUUID(), user.userId(), UUID.randomUUID().toString(), "0".repeat(64), "DEMO_TOPUP", user.walletId(), 100));
        assertEquals(new BigDecimal("0.00"), wallets.balance(user.userId()).availableBalance());
    }

    @Test void migrationsUpgradeFundedMilestoneOneWalletWithoutLosingData() throws Exception {
        String schema = "legacy_upgrade";
        org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema).defaultSchema(schema).target("2").load().migrate();
        UUID userId = UUID.randomUUID(), walletId = UUID.randomUUID(), paymentId = UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
            connection.setSchema(schema);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO app_user VALUES ('" + userId + "','Legacy','7000000001','test-hash',CURRENT_TIMESTAMP)");
                statement.executeUpdate("INSERT INTO wallet VALUES ('" + walletId + "','" + userId + "',75000,CURRENT_TIMESTAMP)");
                statement.executeUpdate("INSERT INTO payment_operation VALUES ('" + paymentId + "','" + userId + "','legacy-key-001','" + "0".repeat(64) + "','DEMO_TOPUP',NULL,'" + walletId + "',75000,CURRENT_TIMESTAMP)");
                statement.executeUpdate("INSERT INTO ledger_entry VALUES ('" + UUID.randomUUID() + "','" + paymentId + "',NULL,'DEMO_ISSUANCE','DEBIT',75000,CURRENT_TIMESTAMP)");
                statement.executeUpdate("INSERT INTO ledger_entry VALUES ('" + UUID.randomUUID() + "','" + paymentId + "','" + walletId + "','WALLET','CREDIT',75000,CURRENT_TIMESTAMP)");
            }
            connection.commit();
        }
        org.flywaydb.core.Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT available_paise,offline_reserved_paise FROM wallet WHERE id='" + walletId + "'")) {
                assertTrue(result.next());
                assertEquals(75000L, result.getLong(1));
                assertEquals(0L, result.getLong(2));
            }
        }
    }
}
