package com.risheesh.offlinepay;

import com.risheesh.offlinepay.auth.*;
import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.offline.OfflineAllowanceService;
import com.risheesh.offlinepay.wallet.WalletService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Milestone2Test {
    @Autowired AuthService auth;
    @Autowired com.risheesh.offlinepay.device.DeviceService devices;
    @Autowired WalletService wallets;
    @Autowired OfflineAllowanceService offline;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private static final AtomicInteger PHONE = new AtomicInteger();
    private record Account(UUID userId, UUID walletId, String mobile) {}

    private Account user() {
        String mobile = "8" + String.format("%09d", PHONE.incrementAndGet());
        var result = auth.signup("Milestone Two", mobile, "DemoPassword123!");
        var pair = com.risheesh.offlinepay.protocol.Ed25519.generate();
        String pub = com.risheesh.offlinepay.protocol.Ed25519.encode(pair.getPublic());
        devices.register(result.userId(), "DEVICE-001", pub, com.risheesh.offlinepay.protocol.Ed25519.sign(pair.getPrivate(),
                com.risheesh.offlinepay.protocol.OfflineProtocol.registration(result.userId(), "DEVICE-001", pub)));
        return new Account(result.userId(), result.walletId(), mobile);
    }
    private void fund(Account user, String amount) {
        wallets.demoTopup(user.userId(), new BigDecimal(amount), UUID.randomUUID().toString());
    }
    private AuthService.TokenResult login(Account user) { return auth.login(user.mobile(), "DemoPassword123!"); }

    @Test void reservesWithoutCreatingMoneyAndBalancesBothLedgerBuckets() {
        var a = user(); fund(a, "750");
        var reserve = offline.reserve(a.userId(), new BigDecimal("300"), "DEVICE-001", UUID.randomUUID().toString());
        var balance = wallets.balance(a.userId());
        assertEquals(new BigDecimal("450.00"), balance.availableBalance());
        assertEquals(new BigDecimal("300.00"), balance.offlineReservedBalance());
        assertEquals(new BigDecimal("750.00"), balance.totalBalance());
        assertEquals("RESERVED", reserve.status());
        assertEquals(reserve, offline.get(a.userId(), reserve.allowanceId()));
        assertEquals(1, offline.list(a.userId(), 0, 20).size());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE operation_id=?", Long.class, reserve.transactionId()));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) FROM ledger_entry WHERE operation_id=?", Long.class, reserve.transactionId()));
        assertEquals(45000L, journal(a.walletId(), "WALLET"));
        assertEquals(30000L, journal(a.walletId(), "OFFLINE_RESERVED"));
    }

    @Test void concurrentReservationRetriesReserveOnce() throws Exception {
        var a = user(); fund(a, "1000"); String key = UUID.randomUUID().toString();
        var ids = parallel(8, () -> offline.reserve(a.userId(), new BigDecimal("300"), "DEVICE-001", key).allowanceId());
        assertEquals(1, new HashSet<>(ids).size());
        assertEquals(new BigDecimal("700.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(new BigDecimal("300.00"), wallets.balance(a.userId()).offlineReservedBalance());
    }

    @Test void reservationAndOnlineDebitCannotBothOverspend() throws Exception {
        var a = user(); var b = user(); fund(a, "1000"); AtomicInteger order = new AtomicInteger();
        var results = parallel(2, () -> {
            try {
                if (order.getAndIncrement() == 0) offline.reserve(a.userId(), new BigDecimal("700"), "DEVICE-001", UUID.randomUUID().toString());
                else wallets.transfer(a.userId(), b.walletId(), new BigDecimal("700"), UUID.randomUUID().toString());
                return "SUCCESS";
            } catch (ApiException ex) { return ex.code(); }
        });
        assertEquals(1, Collections.frequency(results, "SUCCESS"));
        assertEquals(1, Collections.frequency(results, "INSUFFICIENT_FUNDS"));
        assertEquals(new BigDecimal("1000.00"), wallets.balance(a.userId()).totalBalance().add(wallets.balance(b.userId()).totalBalance()));
    }

    @Test void rejectsSpendingReservedBalanceAndKeepsReplayAcrossKinds() {
        var a = user(); var b = user(); fund(a, "750"); String key = UUID.randomUUID().toString();
        var original = offline.reserve(a.userId(), new BigDecimal("300"), "DEVICE-001", key);
        assertEquals(original, offline.reserve(a.userId(), new BigDecimal("300.00"), "DEVICE-001", key));
        assertEquals("INSUFFICIENT_FUNDS", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), b.walletId(), new BigDecimal("500"), UUID.randomUUID().toString())).code());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> offline.reserve(a.userId(), new BigDecimal("300"), "DEVICE-002", key)).code());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> wallets.demoTopup(a.userId(), new BigDecimal("300"), key)).code());
        assertEquals("ALLOWANCE_NOT_FOUND", assertThrows(ApiException.class,
                () -> offline.get(b.userId(), original.allowanceId())).code());
    }

    @Test void failedReservationLeavesNoOperationOrAllocation() {
        var a = user(); fund(a, "100"); String key = UUID.randomUUID().toString();
        assertThrows(ApiException.class, () -> offline.reserve(a.userId(), new BigDecimal("200"), "DEVICE-001", key));
        assertEquals(new BigDecimal("100.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(new BigDecimal("0.00"), wallets.balance(a.userId()).offlineReservedBalance());
        assertTrue(offline.list(a.userId(), 0, 20).isEmpty());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM payment_operation WHERE actor_id=? AND idempotency_key=?", Long.class, a.userId(), key));
        assertEquals("INVALID_DEVICE_ID", assertThrows(ApiException.class,
                () -> offline.reserve(a.userId(), BigDecimal.ONE, "bad|device", UUID.randomUUID().toString())).code());
    }

    @Test void refreshRotatesAndStoresOnlyHashes() {
        var a = user(); var initial = login(a);
        assertEquals(43, initial.refreshToken().length());
        assertEquals(64, jdbc.queryForObject("SELECT token_hash FROM refresh_token WHERE session_id IN (SELECT id FROM refresh_session WHERE user_id=?)", String.class, a.userId()).length());
        var next = auth.refresh(initial.refreshToken());
        assertNotEquals(initial.refreshToken(), next.refreshToken());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE session_id IN (SELECT id FROM refresh_session WHERE user_id=?)", Long.class, a.userId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE consumed_at IS NOT NULL AND session_id IN (SELECT id FROM refresh_session WHERE user_id=?)", Long.class, a.userId()));
        assertTrue(next.refreshExpiresIn() <= initial.refreshExpiresIn());
    }

    @Test void replayRevocationPersistsDespiteUnauthorizedException() {
        var a = user(); var initial = login(a); var rotated = auth.refresh(initial.refreshToken());
        assertEquals("REFRESH_TOKEN_REUSED", assertThrows(ApiException.class, () -> auth.refresh(initial.refreshToken())).code());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_session WHERE user_id=? AND revoked_at IS NOT NULL", Long.class, a.userId()));
        assertEquals("INVALID_REFRESH_TOKEN", assertThrows(ApiException.class, () -> auth.refresh(rotated.refreshToken())).code());
    }

    @Test void concurrentRefreshReuseCannotLeaveUsableDescendant() throws Exception {
        var a = user(); var token = login(a); List<String> results = parallel(2, () -> {
            try { return auth.refresh(token.refreshToken()).refreshToken(); }
            catch (ApiException ex) { return ex.code(); }
        });
        var successful = results.stream().filter(s -> s.length() == 43).toList();
        assertEquals(1, successful.size());
        assertEquals(1, Collections.frequency(results, "REFRESH_TOKEN_REUSED"));
        assertThrows(ApiException.class, () -> auth.refresh(successful.get(0)));
    }

    @Test void logoutRevokesOnlyItsSessionAndIsIdempotent() {
        var a = user(); var first = login(a); var other = login(a);
        auth.logout(first.refreshToken()); auth.logout(first.refreshToken());
        assertThrows(ApiException.class, () -> auth.refresh(first.refreshToken()));
        assertNotNull(auth.refresh(other.refreshToken()).accessToken());
    }

    @Test void expiredOrUnknownRefreshIsRejected() {
        var a = user(); var token = login(a);
        jdbc.update("UPDATE refresh_session SET expires_at=? WHERE user_id=?", java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(10)), a.userId());
        assertThrows(ApiException.class, () -> auth.refresh(token.refreshToken()));
        assertThrows(ApiException.class, () -> auth.refresh("A".repeat(43)));
        assertThrows(ApiException.class, () -> auth.refresh("malformed"));
    }

    @Test void refreshEndpointDoesNotRequireAccessTokenAndIssuesUsableJwt() throws Exception {
        var a = user(); var token = login(a);
        String response = mvc.perform(CsrfTestSupport.authPost(mvc, "/api/v1/auth/refresh")
                .cookie(new jakarta.servlet.http.Cookie("offlinepay_rt", token.refreshToken())))
                .andExpect(status().isOk()).andExpect(jsonPath("refreshToken").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer " + json.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("walletId").value(a.walletId().toString()));
    }

    @Test void reservationEndpointValidatesInputsAndEmitsRequestId() throws Exception {
        var a = user(); fund(a, "500"); String token = login(a).accessToken();
        mvc.perform(post("/api/v1/offline-allowances").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "request-reserve-001").header("X-Request-ID", "manual-request-001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":300,\"deviceId\":\"DEVICE-001\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-ID", "manual-request-001"))
                .andExpect(jsonPath("status").value("RESERVED"));
        mvc.perform(post("/api/v1/offline-allowances").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "request-reserve-002")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1,\"deviceId\":\"bad|device\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void debugLogsContainCorrelationAndCommitButNotTokens(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        var a = user(); var token = login(a); fund(a, "100");
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer " + token.accessToken())
                .header("X-Request-ID", "logs-check-001"))
                .andExpect(status().isOk());
        assertTrue(output.getAll().contains("requestId=logs-check-001"));
        assertTrue(output.getAll().contains("operation_committed"));
        assertFalse(output.getAll().contains(token.accessToken()));
        assertFalse(output.getAll().contains(token.refreshToken()));
        assertFalse(output.getAll().contains("DemoPassword123!"));
    }

    private long journal(UUID wallet, String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END),0) FROM ledger_entry WHERE wallet_id=? AND account_kind=?", Long.class, wallet, account);
    }
    private <T> List<T> parallel(int count, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i=0; i<count; i++) futures.add(pool.submit(() -> { ready.countDown(); start.await(); return work.call(); }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get(20, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
