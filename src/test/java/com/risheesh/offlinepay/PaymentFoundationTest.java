package com.risheesh.offlinepay;

import com.risheesh.offlinepay.auth.AuthService;
import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.wallet.WalletService;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentFoundationTest {
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.risheesh.offlinepay.wallet.WalletRepository walletRepository;
    @Autowired com.risheesh.offlinepay.payment.PaymentOperationRepository paymentRepository;
    @Autowired com.risheesh.offlinepay.ledger.LedgerService ledgerService;
    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired MockMvc mvc;
    private static final java.util.concurrent.atomic.AtomicInteger PHONE = new java.util.concurrent.atomic.AtomicInteger(0);

    AuthService.SignupResult user() {
        String mobile = "9" + String.format("%09d", PHONE.incrementAndGet());
        return auth.signup("Demo User", mobile, "DemoPassword123!");
    }
    void fund(AuthService.SignupResult user, String amount) {
        wallets.demoTopup(user.userId(), new BigDecimal(amount), UUID.randomUUID().toString());
    }
    @Test void transfersAndBalancesJournal() {
        var a = user(); var b = user(); fund(a, "1000");
        var payment = wallets.transfer(a.userId(), b.walletId(), new BigDecimal("250.25"), UUID.randomUUID().toString());
        assertEquals(new BigDecimal("749.75"), wallets.balance(a.userId()).availableBalance());
        assertEquals(new BigDecimal("250.25"), wallets.balance(b.userId()).availableBalance());
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE operation_id=?", Long.class, payment.transactionId()));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) FROM ledger_entry WHERE operation_id=?", Long.class, payment.transactionId()));
        assertEquals(payment.transactionId(), wallets.payment(b.userId(), payment.transactionId()).transactionId());
    }
    @Test void concurrentRetriesExecuteOnce() throws Exception {
        var a = user(); var b = user(); fund(a, "1000");
        String key = UUID.randomUUID().toString();
        var results = parallel(12, () -> wallets.transfer(a.userId(), b.walletId(), new BigDecimal("100"), key).transactionId());
        assertEquals(1, new HashSet<>(results).size());
        assertEquals(new BigDecimal("900.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM payment_operation WHERE actor_id=? AND idempotency_key=?", Long.class, a.userId(), key));
    }
    @Test void preventsConcurrentOverspending() throws Exception {
        var a = user(); var b = user(); fund(a, "1000");
        var results = parallel(2, () -> {
            try { wallets.transfer(a.userId(), b.walletId(), new BigDecimal("700"), UUID.randomUUID().toString()); return "SUCCESS"; }
            catch (ApiException ex) { return ex.code(); }
        });
        assertEquals(1, Collections.frequency(results, "SUCCESS"));
        assertEquals(1, Collections.frequency(results, "INSUFFICIENT_FUNDS"));
        assertEquals(new BigDecimal("300.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(new BigDecimal("700.00"), wallets.balance(b.userId()).availableBalance());
    }
    @Test void rejectsChangedPayloadAndOperationKind() {
        var a = user(); var b = user(); fund(a, "1000");
        String key = UUID.randomUUID().toString();
        wallets.transfer(a.userId(), b.walletId(), new BigDecimal("100"), key);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), b.walletId(), new BigDecimal("200"), key)).code());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
                () -> wallets.demoTopup(a.userId(), new BigDecimal("100"), key)).code());
    }
    @Test void scopesKeysToActorAndReplaysTopups() {
        var a = user(); var b = user(); String key = UUID.randomUUID().toString();
        var p = wallets.demoTopup(a.userId(), new BigDecimal("100"), key);
        assertEquals(p, wallets.demoTopup(a.userId(), new BigDecimal("100.00"), key));
        assertEquals(p.transactionId(), wallets.payment(a.userId(), p.transactionId()).transactionId());
        assertEquals(1, wallets.ledger(a.userId(), 0, 20).size());
        wallets.demoTopup(b.userId(), new BigDecimal("100"), key);
        assertEquals(new BigDecimal("100.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(new BigDecimal("100.00"), wallets.balance(b.userId()).availableBalance());
    }
    @Test void rejectsInvalidInputsAndDisablesFundingOutsideDemo() {
        var a = user(); var b = user();
        assertEquals("INVALID_AMOUNT", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), b.walletId(), new BigDecimal("0.001"), "valid-key-123")).code());
        assertEquals("INVALID_IDEMPOTENCY_KEY", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), b.walletId(), BigDecimal.ONE, "short")).code());
        assertEquals("SELF_TRANSFER", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), a.walletId(), BigDecimal.ONE, "valid-key-123")).code());
        assertEquals("DEMO_DISABLED", assertThrows(ApiException.class,
                () -> new WalletService(walletRepository, paymentRepository, ledgerService, events).demoTopup(a.userId(), BigDecimal.ONE, "valid-key-123")).code());
    }
    @Test void rollsBackWhenCreditFails() {
        var a = user(); var b = user(); fund(a, "1000");
        jdbc.update("UPDATE wallet SET available_paise=? WHERE id=?", Long.MAX_VALUE, b.walletId());
        String key = UUID.randomUUID().toString();
        assertEquals("BALANCE_LIMIT_EXCEEDED", assertThrows(ApiException.class,
                () -> wallets.transfer(a.userId(), b.walletId(), BigDecimal.ONE, key)).code());
        assertEquals(new BigDecimal("1000.00"), wallets.balance(a.userId()).availableBalance());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM payment_operation WHERE actor_id=? AND idempotency_key=?", Long.class, a.userId(), key));
    }
    @Test void oppositeDirectionTransfersPreserveTotal() throws Exception {
        var a = user(); var b = user(); fund(a, "1000"); fund(b, "1000");
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        parallel(10, () -> {
            boolean forward = counter.incrementAndGet() % 2 == 0;
            return wallets.transfer(forward ? a.userId() : b.userId(), forward ? b.walletId() : a.walletId(),
                    BigDecimal.ONE, UUID.randomUUID().toString()).transactionId();
        });
        assertEquals(new BigDecimal("2000.00"), wallets.balance(a.userId()).availableBalance().add(wallets.balance(b.userId()).availableBalance()));
    }
    @Test void authenticatesAndRestrictsOwnership() throws Exception {
        String mobile = "9" + String.format("%09d", PHONE.incrementAndGet());
        var a = auth.signup("Alice", mobile, "DemoPassword123!"); var b = user(); var c = user(); fund(a, "100");
        var payment = wallets.transfer(a.userId(), b.walletId(), BigDecimal.ONE, UUID.randomUUID().toString());
        assertEquals("PAYMENT_NOT_FOUND", assertThrows(ApiException.class, () -> wallets.payment(c.userId(), payment.transactionId())).code());
        String token = auth.login(mobile, "DemoPassword123!").accessToken();
        mvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("walletId").value(a.walletId().toString()));
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer " + token + "broken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/transfer").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"receiverWalletId\":\"" + b.walletId() + "\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/wallets/me/ledger?size=101").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        assertEquals("INVALID_CREDENTIALS", assertThrows(ApiException.class, () -> auth.login(mobile, "wrong-password")).code());
        assertEquals("MOBILE_REGISTERED", assertThrows(ApiException.class,
                () -> auth.signup("Duplicate", mobile, "DemoPassword123!")).code());
    }
    @Test void signupValidationRejectsWeakAndMultibytePasswords() throws Exception {
        mvc.perform(CsrfTestSupport.authPost(mvc, "/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Test\",\"mobileNumber\":\"9000000000\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(CsrfTestSupport.authPost(mvc, "/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Test\",\"mobileNumber\":\"9000000000\",\"password\":\"密码密码密码密码密码密码\"}"))
                .andExpect(status().isBadRequest());
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
