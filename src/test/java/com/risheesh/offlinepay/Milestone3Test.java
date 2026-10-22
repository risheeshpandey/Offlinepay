package com.risheesh.offlinepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.auth.AuthService;
import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.device.DeviceService;
import com.risheesh.offlinepay.offline.*;
import com.risheesh.offlinepay.protocol.*;
import com.risheesh.offlinepay.settlement.OfflineSettlementService;
import com.risheesh.offlinepay.wallet.WalletService;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:milestone3;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
@AutoConfigureMockMvc @ActiveProfiles("test")
class Milestone3Test {
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    @Autowired OfflineAllowanceService offline;
    @Autowired OfflineSettlementService sync;
    @Autowired DeviceService devices;
    @Autowired ServerSigningService signer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private static final AtomicInteger PHONE = new AtomicInteger();
    private record User(UUID id, UUID wallet, String mobile) {}
    private record Fixture(User sender, User receiver, KeyPair pair, SignedAllowance certificate, Instant deadline) {}

    @BeforeEach void clock() {
        time.set(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        when(clock.instant()).thenAnswer(invocation -> time.get());
    }
    private User user() {
        String mobile = "6" + String.format("%09d", PHONE.incrementAndGet());
        var result = auth.signup("Milestone Three", mobile, "DemoPassword123!");
        return new User(result.userId(), result.walletId(), mobile);
    }
    private Fixture fixture() {
        User sender = user(), receiver = user(); KeyPair pair = Ed25519.generate();
        String pub = Ed25519.encode(pair.getPublic());
        devices.register(sender.id(), "DEVICE-M3", pub, Ed25519.sign(pair.getPrivate(), registration(sender.id(), "DEVICE-M3", pub)));
        wallets.demoTopup(sender.id(), new BigDecimal("750"), UUID.randomUUID().toString());
        var allowance = offline.reserve(sender.id(), new BigDecimal("100"), "DEVICE-M3", UUID.randomUUID().toString());
        return new Fixture(sender, receiver, pair, offline.certificate(sender.id(), allowance.allowanceId()), allowance.syncDeadline());
    }
    private PaymentProof proof(Fixture f, long amount, long sequence) {
        return proof(f, f.receiver().wallet(), amount, sequence, UUID.randomUUID(), f.certificate().claims().issuedAtEpochMs());
    }
    private PaymentProof proof(Fixture f, UUID receiver, long amount, long sequence, UUID id, long paidAt) {
        var payment = new PaymentClaims(PAYMENT_VERSION, id, f.certificate().claims().allowanceId(), receiver, amount, sequence, paidAt);
        return new PaymentProof(f.certificate(), payment, Ed25519.sign(f.pair().getPrivate(), canonical(f.certificate(), payment)));
    }
    private String error(Runnable action) { return assertThrows(ApiException.class, action::run).code(); }
    private BigDecimal inr(long paise) { return BigDecimal.valueOf(paise, 2); }
    private OfflineAllowanceService.Allowance allowance(Fixture f) { return offline.get(f.sender().id(), f.certificate().claims().allowanceId()); }
    private String token(User user) { return auth.login(user.mobile(), "DemoPassword123!").accessToken(); }
    private void assertProjection(User user) {
        var balance = wallets.balance(user.id());
        assertEquals(balance.availableBalance(), inr(journal(user.wallet(), "WALLET")));
        assertEquals(balance.offlineReservedBalance(), inr(journal(user.wallet(), "OFFLINE_RESERVED")));
    }
    private long journal(UUID wallet, String kind) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END),0) FROM ledger_entry WHERE wallet_id=? AND account_kind=?", Long.class, wallet, kind);
    }

    @Test void settlesTwoProofsAndConservesMoneyWithSeparateReservedLedger() {
        Fixture f = fixture(); var first = sync.sync(f.receiver().id(), proof(f, 4000, 1));
        assertEquals("SETTLED", first.status()); assertEquals(inr(4000), first.amount());
        assertEquals("PARTIALLY_SETTLED", allowance(f).status());
        assertEquals(inr(6000), allowance(f).remainingAmount());
        assertEquals(inr(65000), wallets.balance(f.sender().id()).availableBalance());
        assertEquals(inr(6000), wallets.balance(f.sender().id()).offlineReservedBalance());
        assertEquals(inr(4000), wallets.balance(f.receiver().id()).availableBalance());
        var second = sync.sync(f.receiver().id(), proof(f, 6000, 2));
        assertNotEquals(first.transactionId(), second.transactionId());
        assertEquals("SETTLED", allowance(f).status()); assertEquals(inr(0), allowance(f).remainingAmount());
        assertEquals(inr(75000), wallets.balance(f.sender().id()).totalBalance().add(wallets.balance(f.receiver().id()).totalBalance()));
        assertProjection(f.sender()); assertProjection(f.receiver());
        assertEquals("OFFLINE_SETTLE", wallets.payment(f.sender().id(), first.transactionId()).kind());
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(CASE WHEN entry_type='CREDIT' THEN amount_paise ELSE -amount_paise END) FROM ledger_entry WHERE operation_id=?", Long.class, first.transactionId()));
    }

    @Test void concurrentSameProofRetriesSettleExactlyOnce() throws Exception {
        Fixture f = fixture(); PaymentProof proof = proof(f, 4000, 1);
        var ids = parallel(12, () -> sync.sync(f.receiver().id(), proof).transactionId());
        assertEquals(1, new HashSet<>(ids).size());
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM offline_settlement WHERE allowance_id=?", Long.class, allowance(f).allowanceId()));
        assertEquals(inr(4000), wallets.balance(f.receiver().id()).availableBalance());
        assertEquals(inr(4000), allowance(f).settledAmount()); assertProjection(f.sender()); assertProjection(f.receiver());
    }

    @Test void samePaymentIdWithDifferentSignedPayloadIsRejected() {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1); sync.sync(f.receiver().id(), p);
        var changed = proof(f, f.receiver().wallet(), 5000, 1, p.payment().paymentId(), p.payment().paidAtEpochMs());
        assertEquals("PAYMENT_ID_CONFLICT", error(() -> sync.sync(f.receiver().id(), changed)));
        assertEquals(inr(4000), wallets.balance(f.receiver().id()).availableBalance());
    }

    @Test void sameSequenceWithDifferentPaymentIdConflicts() {
        Fixture f = fixture(); sync.sync(f.receiver().id(), proof(f, 4000, 1));
        assertEquals("SEQUENCE_CONFLICT", error(() -> sync.sync(f.receiver().id(), proof(f, 2000, 1))));
        assertEquals(inr(6000), allowance(f).remainingAmount());
    }

    @Test void competingDifferentProofsCannotOverspendAllowance() throws Exception {
        Fixture f = fixture(); var p1 = proof(f, 7000, 1); var p2 = proof(f, 7000, 2); AtomicInteger order = new AtomicInteger();
        var results = parallel(2, () -> {
            try { sync.sync(f.receiver().id(), order.getAndIncrement() == 0 ? p1 : p2); return "OK"; }
            catch (ApiException ex) { return ex.code(); }
        });
        assertEquals(1, Collections.frequency(results, "OK")); assertEquals(1, Collections.frequency(results, "ALLOWANCE_EXHAUSTED"));
        assertEquals(inr(3000), allowance(f).remainingAmount()); assertProjection(f.sender()); assertProjection(f.receiver());
    }

    @Test void distinctReceiversStillShareOneAllowanceBudget() throws Exception {
        Fixture f = fixture(); User other = user();
        var one = proof(f, 7000, 1);
        var two = proof(f, other.wallet(), 7000, 2, UUID.randomUUID(), f.certificate().claims().issuedAtEpochMs());
        AtomicInteger order = new AtomicInteger();
        var results = parallel(2, () -> {
            boolean first = order.getAndIncrement() == 0;
            try { sync.sync(first ? f.receiver().id() : other.id(), first ? one : two); return "OK"; }
            catch (ApiException ex) { return ex.code(); }
        });
        assertEquals(1, Collections.frequency(results, "OK"));
        assertEquals(inr(7000), wallets.balance(f.receiver().id()).totalBalance().add(wallets.balance(other.id()).totalBalance()));
        assertProjection(f.sender()); assertProjection(f.receiver()); assertProjection(other);
    }

    @Test void outOfOrderSequenceIsAllowedButCumulativeLimitStillHolds() {
        Fixture f = fixture(); sync.sync(f.receiver().id(), proof(f, 6000, 2));
        sync.sync(f.receiver().id(), proof(f, 4000, 1));
        assertEquals("ALLOWANCE_EXHAUSTED", error(() -> sync.sync(f.receiver().id(), proof(f, 1, 3))));
    }

    @Test void rejectsWrongReceiverTamperingAndUntrustedCertificateWithoutChanges() {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1); User third = user();
        assertEquals("INVALID_OFFLINE_PROOF", error(() -> sync.sync(third.id(), p)));
        assertEquals("INVALID_OFFLINE_PROOF", error(() -> sync.sync(f.receiver().id(), new PaymentProof(p.allowance(), p.payment(), "A".repeat(88)))));
        var badCertificate = new SignedAllowance(f.certificate().claims(), "A".repeat(88));
        assertEquals("INVALID_OFFLINE_PROOF", error(() -> sync.sync(f.receiver().id(), new PaymentProof(badCertificate, p.payment(), p.signature()))));
        assertEquals(inr(10000), allowance(f).remainingAmount()); assertEquals(inr(0), wallets.balance(f.receiver().id()).availableBalance());
    }

    @Test void rejectsPaymentOutsideSignedTimeWindowEvenDuringGrace() {
        Fixture f = fixture(); var c = f.certificate().claims();
        time.set(Instant.ofEpochMilli(c.expiresAtEpochMs()).plusSeconds(1));
        for (long paid : new long[] {c.issuedAtEpochMs() - 1, c.expiresAtEpochMs(), time.get().plusSeconds(1).toEpochMilli()}) {
            var invalid = proof(f, f.receiver().wallet(), 4000, 1, UUID.randomUUID(), paid);
            assertEquals("INVALID_OFFLINE_PROOF", error(() -> sync.sync(f.receiver().id(), invalid)));
        }
    }

    @Test void acceptsPreviouslyCreatedProofDuringGraceButLocalAcceptanceRemainsExpired() {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1);
        time.set(Instant.ofEpochMilli(f.certificate().claims().expiresAtEpochMs()).plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(p, signer.publicKey(), f.receiver().wallet(), time.get()));
        assertEquals("SETTLED", sync.sync(f.receiver().id(), p).status());
    }

    @Test void rejectsNewClaimsExactlyAtDeadlineEvenBeforeRelease() {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1); time.set(f.deadline());
        assertEquals("SYNC_DEADLINE_PASSED", error(() -> sync.sync(f.receiver().id(), p)));
        assertEquals(inr(10000), wallets.balance(f.sender().id()).offlineReservedBalance());
    }

    @Test void unusedReleaseRequiresDeadlineAndOwnerThenReturnsStableResult() {
        Fixture f = fixture(); UUID id = f.certificate().claims().allowanceId();
        assertEquals("SYNC_WINDOW_OPEN", error(() -> sync.release(f.sender().id(), id)));
        time.set(Instant.ofEpochMilli(f.certificate().claims().expiresAtEpochMs()));
        assertEquals("SYNC_WINDOW_OPEN", error(() -> sync.release(f.sender().id(), id)));
        time.set(f.deadline());
        assertEquals("ALLOWANCE_NOT_FOUND", error(() -> sync.release(f.receiver().id(), id)));
        var release = sync.release(f.sender().id(), id);
        assertEquals(inr(10000), release.releasedAmount()); assertEquals(release, sync.release(f.sender().id(), id));
        assertEquals("RELEASED", allowance(f).status());
        assertEquals(inr(75000), wallets.balance(f.sender().id()).availableBalance()); assertProjection(f.sender());
        assertEquals("OFFLINE_RELEASE", wallets.payment(f.sender().id(), release.transactionId()).kind());
    }

    @Test void partialSettlementReleasesOnlyUnusedFundsAndOldRetryStillSucceeds() {
        Fixture f = fixture(); PaymentProof first = proof(f, 4000, 1), unpaid = proof(f, 6000, 2);
        var settled = sync.sync(f.receiver().id(), first); time.set(f.deadline());
        var release = sync.release(f.sender().id(), allowance(f).allowanceId());
        assertEquals(inr(6000), release.releasedAmount());
        assertEquals(inr(71000), wallets.balance(f.sender().id()).availableBalance());
        assertEquals(inr(4000), wallets.balance(f.receiver().id()).availableBalance());
        assertEquals(settled, sync.sync(f.receiver().id(), first));
        assertEquals("SYNC_DEADLINE_PASSED", error(() -> sync.sync(f.receiver().id(), unpaid)));
        assertProjection(f.sender()); assertProjection(f.receiver());
    }

    @Test void concurrentReleaseRetriesMoveUnusedFundsOnce() throws Exception {
        Fixture f = fixture(); time.set(f.deadline()); UUID id = allowance(f).allowanceId();
        var operations = parallel(8, () -> sync.release(f.sender().id(), id).transactionId());
        assertEquals(1, new HashSet<>(operations).size()); assertEquals(inr(75000), wallets.balance(f.sender().id()).availableBalance());
        assertProjection(f.sender());
    }

    @Test void settlementAndReleaseAtDeadlineCannotDoubleSpendReserve() throws Exception {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1); UUID id = allowance(f).allowanceId(); time.set(f.deadline());
        AtomicInteger order = new AtomicInteger();
        var results = parallel(2, () -> {
            try {
                if (order.getAndIncrement() == 0) sync.sync(f.receiver().id(), p);
                else sync.release(f.sender().id(), id);
                return "OK";
            } catch (ApiException ex) { return ex.code(); }
        });
        assertEquals(1, Collections.frequency(results, "SYNC_DEADLINE_PASSED")); assertEquals(1, Collections.frequency(results, "OK"));
        assertEquals(inr(0), wallets.balance(f.receiver().id()).availableBalance()); assertProjection(f.sender());
    }

    @Test void fullySettledAllowanceHasNothingToRelease() {
        Fixture f = fixture(); sync.sync(f.receiver().id(), proof(f, 10000, 1)); time.set(f.deadline());
        var release = sync.release(f.sender().id(), allowance(f).allowanceId());
        assertNull(release.transactionId()); assertEquals(inr(0), release.releasedAmount());
        assertEquals("SETTLED", allowance(f).status());
    }

    @Test void receiverOverflowRollsBackAllowanceReserveReceiptAndLedgerTogether() {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1);
        // Deliberate DB fault injection, not a valid funded wallet history.
        jdbc.update("UPDATE wallet SET available_paise=? WHERE id=?", Long.MAX_VALUE, f.receiver().wallet());
        assertEquals("BALANCE_LIMIT_EXCEEDED", error(() -> sync.sync(f.receiver().id(), p)));
        assertEquals(inr(10000), allowance(f).remainingAmount()); assertEquals(inr(0), allowance(f).settledAmount());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM offline_settlement WHERE payment_id=?", Long.class, p.payment().paymentId()));
        jdbc.update("UPDATE wallet SET available_paise=0 WHERE id=?", f.receiver().wallet());
        assertEquals("SETTLED", sync.sync(f.receiver().id(), p).status());
        assertProjection(f.sender()); assertProjection(f.receiver());
    }

    @Test void revocationBlocksUnsettledProofButNotPriorCommittedRetry() {
        Fixture f = fixture(); PaymentProof first = proof(f, 4000, 1), second = proof(f, 6000, 2);
        var original = sync.sync(f.receiver().id(), first); devices.revoke(f.sender().id(), "DEVICE-M3");
        assertEquals(original, sync.sync(f.receiver().id(), first));
        assertEquals("DEVICE_REVOKED", error(() -> sync.sync(f.receiver().id(), second)));
        assertEquals(inr(6000), allowance(f).remainingAmount());
    }

    @Test void legacyUnsignedFundsCanBeReleasedButNotSettledWithOldCertificate() {
        Fixture f = fixture(); UUID id = allowance(f).allowanceId();
        jdbc.update("UPDATE offline_allowance SET device_public_key=NULL, server_key_id=NULL, server_signature=NULL WHERE id=?", id);
        assertEquals("CERTIFICATE_MISMATCH", error(() -> sync.sync(f.receiver().id(), proof(f, 4000, 1))));
        time.set(f.deadline()); assertEquals(inr(10000), sync.release(f.sender().id(), id).releasedAmount()); assertProjection(f.sender());
    }

    @Test void httpSyncNeedsReceiverBearerHasIstReceiptAndBothPartiesCanRead() throws Exception {
        Fixture f = fixture(); PaymentProof proof = proof(f, 4000, 1); String bob = token(f.receiver());
        mvc.perform(post("/api/v1/offline-sync/payments").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proof)))
                .andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/api/v1/offline-sync/payments").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(proof)))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("SETTLED")).andExpect(jsonPath("amount").value(40))
                .andReturn().getResponse();
        assertTrue(json.readTree(response.getContentAsString()).get("settledAt").asText().endsWith("+05:30"));
        for (User viewer : List.of(f.sender(), f.receiver())) {
            mvc.perform(get("/api/v1/offline-sync/payments/" + proof.payment().paymentId()).header("Authorization", "Bearer " + token(viewer)))
                    .andExpect(status().isOk());
        }
        User third = user();
        assertEquals("SETTLEMENT_NOT_FOUND", error(() -> sync.get(third.id(), proof.payment().paymentId())));
        mvc.perform(post("/api/v1/offline-sync/payments").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payment\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_OFFLINE_PROOF"));
    }

    @Test void concurrentGlobalPaymentIdCollisionCannotMergeAndMoveMoneyTwice() throws Exception {
        Fixture first = fixture(), second = fixture(); UUID id = UUID.randomUUID();
        PaymentProof p1 = proof(first, first.receiver().wallet(), 4000, 1, id, first.certificate().claims().issuedAtEpochMs());
        PaymentProof p2 = proof(second, second.receiver().wallet(), 4000, 1, id, second.certificate().claims().issuedAtEpochMs());
        String at1 = token(first.receiver()), at2 = token(second.receiver()); AtomicInteger order = new AtomicInteger();
        var statuses = parallel(2, () -> {
            boolean one = order.getAndIncrement() == 0;
            return mvc.perform(post("/api/v1/offline-sync/payments").header("Authorization", "Bearer " + (one ? at1 : at2))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(one ? p1 : p2)))
                    .andReturn().getResponse().getStatus();
        });
        assertEquals(1, Collections.frequency(statuses, 200)); assertEquals(1, Collections.frequency(statuses, 409));
        assertEquals(inr(4000), wallets.balance(first.receiver().id()).totalBalance().add(wallets.balance(second.receiver().id()).totalBalance()));
        assertProjection(first.sender()); assertProjection(first.receiver()); assertProjection(second.sender()); assertProjection(second.receiver());
    }

    @Test void anotherAllowanceReserveCannotSubsidizeOverspentClaims() {
        Fixture f = fixture();
        offline.reserve(f.sender().id(), new BigDecimal("200"), "DEVICE-M3", UUID.randomUUID().toString());
        sync.sync(f.receiver().id(), proof(f, 7000, 1));
        assertEquals("ALLOWANCE_EXHAUSTED", error(() -> sync.sync(f.receiver().id(), proof(f, 7000, 2))));
        assertEquals(inr(23000), wallets.balance(f.sender().id()).offlineReservedBalance());
        assertEquals(inr(3000), allowance(f).remainingAmount()); assertProjection(f.sender());
    }

    @Test void persistedProofSnapshotKeepsSignaturesAndContainsNoCredentials() throws Exception {
        Fixture f = fixture(); PaymentProof p = proof(f, 4000, 1); sync.sync(f.receiver().id(), p);
        String stored = jdbc.queryForObject("SELECT proof_json FROM offline_settlement WHERE payment_id=?", String.class, p.payment().paymentId());
        assertEquals(p, json.readValue(stored, PaymentProof.class));
        assertFalse(stored.contains("private-key")); assertFalse(stored.contains("refreshToken"));
        assertFalse(stored.contains("DemoPassword123!"));
    }

    private <T> List<T> parallel(int count, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count); CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { ready.countDown(); start.await(); return work.call(); }));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<T> results = new ArrayList<>(); for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
