package com.risheesh.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.Idempotency;
import com.risheesh.offlinepay.messaging.*;
import com.risheesh.offlinepay.notification.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"eureka.client.enabled=false","spring.datasource.url=jdbc:h2:mem:m5notice;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.hikari.connection-init-sql=",
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.kafka.listener.auto-startup=false",
    "offlinepay.workers-enabled=false", "offlinepay.internal-token=test-only-token-at-least-32-characters"})
@AutoConfigureMockMvc
class NotificationBoundaryTest {
    @Autowired RemoteInbox inbox;
    @Autowired InboxTransaction tx;
    @Autowired NotificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @MockitoBean CoreClient core;
    PaymentMessage message(UUID sender, UUID receiver) {
        UUID id = UUID.randomUUID(); return new PaymentMessage(1,id,id,"TRANSFER",sender,receiver,100,Instant.now());
    }
    String valid(PaymentMessage m) throws Exception {
        String payload = json.writeValueAsString(m);
        when(core.event(eq(m.eventId()),anyString())).thenReturn(new CoreClient.EventDigest(m.eventId(),Idempotency.fingerprint(payload)));
        return payload;
    }
    @Test void validSourceCreatesTwoNoticesAndDuplicateCreatesNone() throws Exception {
        var m = message(UUID.randomUUID(),UUID.randomUUID());String payload = valid(m);
        inbox.accept(m.eventId().toString(),payload);inbox.accept(m.eventId().toString(),payload);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE event_id=?",Integer.class,m.eventId()));
        assertEquals(1,service.list(m.senderUserId(),0,20).size());
    }
    @Test void tamperedBrokerPayloadAndUnknownVersionAreRejected() throws Exception {
        var m = message(UUID.randomUUID(),UUID.randomUUID());String payload = valid(m);
        assertThrows(InvalidPaymentMessage.class, () -> inbox.accept(m.eventId().toString(),payload.replace("100,", "200,")));
        assertThrows(InvalidPaymentMessage.class, () -> inbox.accept(m.eventId().toString(),"not-json"));
        assertTrue(service.list(m.senderUserId(),0,20).isEmpty());
    }
    @Test void concurrentDeliveryHasOneLocalMarker() throws Exception {
        var m = message(UUID.randomUUID(),UUID.randomUUID());String hash = "a".repeat(64);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for(int i=0;i<8;i++) tasks.add(() -> { tx.store(m,hash);return null; });
            for(var future: pool.invokeAll(tasks)) future.get();
        } finally { pool.shutdownNow(); }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE event_id=?",Integer.class,m.eventId()));
    }
    @Test void migrationModeDoesNotStartConsumers() {
        assertEquals(2,listeners.getListenerContainers().size());
        assertTrue(listeners.getListenerContainers().stream().noneMatch(c -> c.isRunning()));
    }
    @Test void invalidBearerAndMissingBearerAreRejected() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        when(core.session("Bearer bad")).thenThrow(feign.FeignException.errorStatus("session",
            feign.Response.builder().status(401).reason("Unauthorized").request(feign.Request.create(feign.Request.HttpMethod.GET,"http://core",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null)).build()));
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer bad")).andExpect(status().isUnauthorized());
    }
    @Test void identityOutageDoesNotGrantAccess() throws Exception {
        when(core.session("Bearer outage")).thenThrow(feign.FeignException.errorStatus("session",
            feign.Response.builder().status(503).reason("Unavailable").request(feign.Request.create(feign.Request.HttpMethod.GET,"http://core",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null)).build()));
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer outage")).andExpect(status().isServiceUnavailable());
    }
    @Test void identityRateLimitRemains429() throws Exception {
        when(core.session("Bearer limited")).thenThrow(feign.FeignException.errorStatus("session",
            feign.Response.builder().status(429).reason("Limited").request(feign.Request.create(feign.Request.HttpMethod.GET,"http://core",Map.of(),null,java.nio.charset.StandardCharsets.UTF_8,null)).build()));
        mvc.perform(get("/api/v1/notifications").header("Authorization","Bearer limited")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
    }
    @Test void failedNoticeInsertRollsBackConsumedMarker() {
        var bad = message(UUID.randomUUID(),UUID.randomUUID());
        var invalid = new PaymentMessage(1,bad.eventId(),bad.transactionId(),null,bad.senderUserId(),bad.receiverUserId(),100,Instant.now());
        assertThrows(RuntimeException.class, () -> tx.store(invalid,"b".repeat(64)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM consumed_payment_event WHERE event_id=?",Integer.class,bad.eventId()));
    }
    @Test void ownershipReadAndPaginationSurviveRemoteAuthentication() throws Exception {
        var m = message(UUID.randomUUID(),UUID.randomUUID());inbox.accept(m.eventId().toString(),valid(m));
        UUID notice = service.list(m.senderUserId(),0,20).get(0).notificationId();
        when(core.session("Bearer alice")).thenReturn(new CoreClient.Session(m.senderUserId()));
        when(core.session("Bearer bob")).thenReturn(new CoreClient.Session(m.receiverUserId()));
        mvc.perform(post("/api/v1/notifications/"+notice+"/read").header("Authorization","Bearer bob")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/notifications/"+notice+"/read").header("Authorization","Bearer alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").isNotEmpty());
        mvc.perform(get("/api/v1/notifications?size=101").header("Authorization","Bearer alice")).andExpect(status().isBadRequest());
    }
}
