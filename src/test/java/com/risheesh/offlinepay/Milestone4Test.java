package com.risheesh.offlinepay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.auth.AuthService;
import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.messaging.*;
import com.risheesh.offlinepay.notification.*;
import com.risheesh.offlinepay.wallet.WalletService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:milestone4;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "offlinepay.notifications.webhook-url=http://localhost:1/test"
})
@AutoConfigureMockMvc @ActiveProfiles("test")
class Milestone4Test {
    @Autowired AuthService auth;
    @Autowired WalletService wallets;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxTransactions relay;
    @Autowired NotificationInbox inbox;
    @Autowired NotificationService notices;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationDeliveryTransactions deliveries;
    @Autowired DeadLetterStore deadLetters;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> time = new AtomicReference<>();
    private static final AtomicInteger PHONE = new AtomicInteger();
    @BeforeEach void clock() {
        time.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); when(clock.instant()).thenAnswer(call -> time.get());
    }
    private AuthService.SignupResult user() {
        return auth.signup("M4 learner", "7" + String.format("%09d", PHONE.incrementAndGet()), "DemoPassword123!");
    }
    private OutboxEvent topup(AuthService.SignupResult user) {
        var payment = wallets.demoTopup(user.userId(), new BigDecimal("100"), UUID.randomUUID().toString());
        var event = outbox.findById(payment.transactionId()).orElseThrow();
        // Anchor the test clock to the committed event; hashing/DB may take over one second on a busy host.
        time.set(event.getNextAttemptAt());
        return event;
    }
    private void accept(OutboxEvent event) { inbox.accept(event.getId().toString(), event.getPayload()); }
    private long count(String table, UUID event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_id=?", Long.class, event);
    }
    @Test void paymentAndOutboxCommitTogetherAndReplayDoesNotAddEvent() throws Exception {
        var user = user(); String key = UUID.randomUUID().toString();
        var first = wallets.demoTopup(user.userId(), new BigDecimal("100"), key);
        var retry = wallets.demoTopup(user.userId(), new BigDecimal("100"), key);
        assertEquals(first.transactionId(), retry.transactionId());
        var row = outbox.findById(first.transactionId()).orElseThrow();
        assertEquals("PENDING", row.getStatus());
        var message = json.readValue(row.getPayload(), PaymentMessage.class);
        assertEquals(first.transactionId(), message.eventId());
        assertEquals(user.userId(), message.receiverUserId());
        assertFalse(row.getPayload().contains("password"));
        assertFalse(row.getPayload().contains("accessToken"));
    }
    @Test void outerRollbackRemovesBalanceOperationAndOutbox() {
        var user = user(); long before = outbox.count();
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).execute(status -> {
            wallets.demoTopup(user.userId(), new BigDecimal("10"), UUID.randomUUID().toString());
            throw new IllegalStateException("Simulated failure before commit");
        }));
        assertEquals(before, outbox.count());
        assertEquals(0, wallets.balance(user.userId()).availableBalance().compareTo(BigDecimal.ZERO));
    }
    @Test void onlyOneWorkerClaimsAndExpiredLeaseCanBeReclaimed() {
        var event = topup(user()); time.set(time.get().plusSeconds(1));
        var first = relay.claim(event.getId()).orElseThrow();
        assertTrue(relay.claim(event.getId()).isEmpty());
        time.set(time.get().plusSeconds(61));
        var second = relay.claim(event.getId()).orElseThrow();
        relay.finish(first, null); // Old owner cannot change a newer lease.
        assertEquals("PROCESSING", outbox.findById(event.getId()).orElseThrow().getStatus());
        relay.finish(second, null);
        assertEquals("PUBLISHED", outbox.findById(event.getId()).orElseThrow().getStatus());
    }
    @Test void failedPublishRetriesThenParksWithoutChangingMoney() {
        var user = user(); var event = topup(user); time.set(time.get().plusSeconds(1));
        for (int attempt=0; attempt<8; attempt++) {
            var claim = relay.claim(event.getId()).orElseThrow();
            relay.finish(claim, "TimeoutException");
            time.set(time.get().plusSeconds(301));
        }
        assertEquals("FAILED", outbox.findById(event.getId()).orElseThrow().getStatus());
        assertTrue(relay.claim(event.getId()).isEmpty());
        assertEquals(new BigDecimal("100.00"), wallets.balance(user.userId()).availableBalance());
    }
    @Test void publishBeforeMarkCrashAndRedeliveryCreateOneInboxNotice() {
        var user = user(); var event = topup(user);
        accept(event); // Broker delivered, relay crashes before PUBLISHED marker.
        accept(event);
        assertEquals(1, count("consumed_payment_event", event.getId()));
        assertEquals(1, count("user_notification", event.getId()));
        assertEquals(1, notices.list(user.userId(), 0, 20).size());
    }
    @Test void concurrentDuplicateEventsProduceOneNotification() throws Exception {
        var event = topup(user()); var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i=0;i<12;i++) tasks.add(() -> { accept(event); return null; });
            for (var result : pool.invokeAll(tasks)) result.get();
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("user_notification", event.getId()));
    }
    @Test void transferNotifiesBothPartiesButSelfOperationNotifiesOnce() {
        var sender = user(); var receiver = user(); topup(sender);
        var transfer = wallets.transfer(sender.userId(), receiver.walletId(), new BigDecimal("40"), UUID.randomUUID().toString());
        accept(outbox.findById(transfer.transactionId()).orElseThrow());
        assertEquals(2, count("user_notification", transfer.transactionId()));
        assertEquals(1, notices.list(sender.userId(), 0, 20).size());
        assertEquals(1, notices.list(receiver.userId(), 0, 20).size());
    }
    @Test void invalidPayloadKeyAndForgedAmountAreRejectedWithoutInbox() throws Exception {
        var event = topup(user());
        assertThrows(InvalidPaymentMessage.class, () -> inbox.accept(event.getId().toString(), "not-json"));
        assertThrows(InvalidPaymentMessage.class, () -> inbox.accept(UUID.randomUUID().toString(), event.getPayload()));
        var tree = json.readTree(event.getPayload()); ((com.fasterxml.jackson.databind.node.ObjectNode)tree).put("amountPaise", 1);
        assertThrows(InvalidPaymentMessage.class, () -> inbox.accept(event.getId().toString(), tree.toString()));
        assertEquals(0, count("consumed_payment_event", event.getId()));
    }
    @Test void noticeReadIsIdempotentAndPrivate() throws Exception {
        var owner = user(); var other = user(); var event = topup(owner); accept(event);
        var notice = notices.list(owner.userId(), 0, 20).get(0);
        var first = notices.read(owner.userId(), notice.notificationId());
        time.set(time.get().plusSeconds(5));
        assertEquals(first.readAt(), notices.read(owner.userId(), notice.notificationId()).readAt());
        assertThrows(ApiException.class, () -> notices.read(other.userId(), notice.notificationId()));
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(owner.userId().toString()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].transactionId").value(event.getId().toString()));
        mvc.perform(post("/api/v1/notifications/"+notice.notificationId()+"/read")
            .with(jwt().jwt(j -> j.subject(other.userId().toString())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notifications?size=101").with(jwt().jwt(j -> j.subject(owner.userId().toString()))))
            .andExpect(status().isBadRequest());
    }
    @Test void notificationFailureIsDurableAndDoesNotChangeBalanceOrReadStatus() {
        var owner = user(); var event = topup(owner); accept(event);
        var notice = notices.list(owner.userId(),0,20).get(0);
        for (int i=0;i<8;i++) {
            var claim = deliveries.claim(notice.notificationId()).orElseThrow();
            deliveries.finish(claim, "ResourceAccessException");
            time.set(time.get().plusSeconds(301));
        }
        var row = notifications.findById(notice.notificationId()).orElseThrow();
        assertEquals("FAILED", row.getDeliveryStatus()); assertNull(row.getReadAt());
        assertEquals(new BigDecimal("100.00"), wallets.balance(owner.userId()).availableBalance());
    }
    @Test void deliveryLeasePreventsStaleCompletionAndSuccessfulDeliveryCanStillBeUnread() {
        var owner = user(); var event = topup(owner); accept(event);
        UUID id = notices.list(owner.userId(),0,20).get(0).notificationId();
        var first = deliveries.claim(id).orElseThrow(); assertTrue(deliveries.claim(id).isEmpty());
        time.set(time.get().plusSeconds(61)); var second = deliveries.claim(id).orElseThrow();
        deliveries.finish(first, null);
        assertEquals("PROCESSING", notifications.findById(id).orElseThrow().getDeliveryStatus());
        deliveries.finish(second, null);
        var row = notifications.findById(id).orElseThrow(); assertEquals("DELIVERED", row.getDeliveryStatus()); assertNull(row.getReadAt());
    }
    @Test void deadLetterOffsetRedeliveryArchivesOnce() {
        deadLetters.archive("test.DLT", 0, 12, "bad", "broken");
        deadLetters.archive("test.DLT", 0, 12, "bad", "broken");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_event WHERE topic='test.DLT' AND offset_number=12", Integer.class));
    }
}
