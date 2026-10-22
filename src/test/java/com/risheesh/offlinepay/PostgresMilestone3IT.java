package com.risheesh.offlinepay;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PostgresMilestone3IT extends Milestone3Test {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add("spring.flyway.enabled", () -> true);
        properties.add("spring.sql.init.mode", () -> "never");
    }
    @org.junit.jupiter.api.Test
    void upgradeV5PreservesSignedReservationAndBalances() throws Exception {
        String schema = "upgrade_v5";
        org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("5").load().migrate();
        java.util.UUID user = java.util.UUID.randomUUID(), wallet = java.util.UUID.randomUUID();
        java.util.UUID topup = java.util.UUID.randomUUID(), reserve = java.util.UUID.randomUUID(), allowance = java.util.UUID.randomUUID();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema); connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                sql.executeUpdate("INSERT INTO app_user VALUES ('" + user + "','Upgrade','6111111111','test-hash',CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO wallet(id,user_id,available_paise,offline_reserved_paise,created_at) VALUES ('" + wallet + "','" + user + "',65000,10000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO payment_operation(id,actor_id,idempotency_key,request_hash,kind,receiver_wallet_id,amount_paise,created_at) VALUES ('" + topup + "','" + user + "','upgrade-topup','" + "0".repeat(64) + "','DEMO_TOPUP','" + wallet + "',75000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO payment_operation(id,actor_id,idempotency_key,request_hash,kind,sender_wallet_id,receiver_wallet_id,amount_paise,created_at) VALUES ('" + reserve + "','" + user + "','upgrade-reserve','" + "1".repeat(64) + "','OFFLINE_RESERVE','" + wallet + "','" + wallet + "',10000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO ledger_entry VALUES ('" + java.util.UUID.randomUUID() + "','" + topup + "',NULL,'DEMO_ISSUANCE','DEBIT',75000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO ledger_entry VALUES ('" + java.util.UUID.randomUUID() + "','" + topup + "','" + wallet + "','WALLET','CREDIT',75000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO ledger_entry VALUES ('" + java.util.UUID.randomUUID() + "','" + reserve + "','" + wallet + "','WALLET','DEBIT',10000,CURRENT_TIMESTAMP)");
                sql.executeUpdate("INSERT INTO ledger_entry VALUES ('" + java.util.UUID.randomUUID() + "','" + reserve + "','" + wallet + "','OFFLINE_RESERVED','CREDIT',10000,CURRENT_TIMESTAMP)");
                // Opaque fixture fields check byte preservation, not signature validity.
                sql.executeUpdate("INSERT INTO offline_allowance(id,user_id,wallet_id,operation_id,device_id,allocated_paise,status,created_at,expires_at,device_public_key,server_key_id,server_signature) VALUES ('" + allowance + "','" + user + "','" + wallet + "','" + reserve + "','LEGACY',10000,'RESERVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 day','fixture-public-key','" + "2".repeat(64) + "','fixture-signature')");
            }
            connection.commit();
        }
        org.flywaydb.core.Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).load().migrate();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema);
            try (var sql = connection.createStatement(); var row = sql.executeQuery("SELECT w.available_paise,w.offline_reserved_paise,a.settled_paise,a.released_paise,EXTRACT(EPOCH FROM (a.sync_deadline-a.expires_at)),a.server_signature FROM wallet w JOIN offline_allowance a ON a.wallet_id=w.id")) {
                org.junit.jupiter.api.Assertions.assertTrue(row.next());
                org.junit.jupiter.api.Assertions.assertEquals(65000, row.getLong(1));
                org.junit.jupiter.api.Assertions.assertEquals(10000, row.getLong(2));
                org.junit.jupiter.api.Assertions.assertEquals(0, row.getLong(3));
                org.junit.jupiter.api.Assertions.assertEquals(0, row.getLong(4));
                org.junit.jupiter.api.Assertions.assertEquals(172800, row.getLong(5));
                org.junit.jupiter.api.Assertions.assertEquals("fixture-signature", row.getString(6));
            }
        }
    }
    @org.junit.jupiter.api.Test
    void databaseProtectsAcceptedReceiptFromMutation() {
        var sender = auth.signup("Receipt sender", "6222222221", "DemoPassword123!");
        var receiver = auth.signup("Receipt receiver", "6222222222", "DemoPassword123!");
        var pair = com.risheesh.offlinepay.protocol.Ed25519.generate();
        String pub = com.risheesh.offlinepay.protocol.Ed25519.encode(pair.getPublic());
        devices.register(sender.userId(), "PG-DEVICE", pub, com.risheesh.offlinepay.protocol.Ed25519.sign(pair.getPrivate(),
                com.risheesh.offlinepay.protocol.OfflineProtocol.registration(sender.userId(), "PG-DEVICE", pub)));
        wallets.demoTopup(sender.userId(), new java.math.BigDecimal("10"), java.util.UUID.randomUUID().toString());
        var allowance = offline.reserve(sender.userId(), new java.math.BigDecimal("3"), "PG-DEVICE", java.util.UUID.randomUUID().toString());
        var certificate = offline.certificate(sender.userId(), allowance.allowanceId());
        var payment = new com.risheesh.offlinepay.protocol.OfflineProtocol.PaymentClaims(
                com.risheesh.offlinepay.protocol.OfflineProtocol.PAYMENT_VERSION, java.util.UUID.randomUUID(), allowance.allowanceId(),
                receiver.walletId(), 100, 1, clock.instant().toEpochMilli());
        var proof = new com.risheesh.offlinepay.protocol.OfflineProtocol.PaymentProof(certificate, payment,
                com.risheesh.offlinepay.protocol.Ed25519.sign(pair.getPrivate(),
                    com.risheesh.offlinepay.protocol.OfflineProtocol.canonical(certificate, payment)));
        sync.sync(receiver.userId(), proof);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE offline_settlement SET proof_hash=? WHERE payment_id=?", "0".repeat(64), payment.paymentId()));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("DELETE FROM offline_settlement WHERE payment_id=?", payment.paymentId()));
    }
}
