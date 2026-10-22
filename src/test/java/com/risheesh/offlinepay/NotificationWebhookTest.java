package com.risheesh.offlinepay;

import com.risheesh.offlinepay.notification.*;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.bulkhead.*;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:webhook4;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class NotificationWebhookTest {
    static HttpServer server;
    static AtomicInteger calls = new AtomicInteger();
    static AtomicInteger status = new AtomicInteger(204);
    static AtomicReference<String> key = new AtomicReference<>();
    static AtomicLong delay = new AtomicLong();
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { var t=new Thread(r); t.setDaemon(true); return t; }));
            server.createContext("/notify", exchange -> {
                calls.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                exchange.getRequestBody().readAllBytes();
                try { Thread.sleep(delay.get()); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                try { exchange.sendResponseHeaders(status.get(),-1); } finally { exchange.close(); }
            });
            server.start();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("offlinepay.notifications.webhook-url",() -> "http://127.0.0.1:"+server.getAddress().getPort()+"/notify");
    }
    @Autowired NotificationWebhook webhook;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired BulkheadRegistry bulkheads;
    @BeforeEach void reset() { calls.set(0); status.set(204); delay.set(0); breakers.circuitBreaker("notificationProvider").reset(); }
    @AfterAll static void stop() { server.stop(0); }
    private NotificationDeliveryTransactions.Delivery delivery() {
        return new NotificationDeliveryTransactions.Delivery(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"OFFLINE_SETTLE",4000);
    }
    @Test void sendsStableProviderIdempotencyKey() {
        var delivery = delivery(); webhook.send(delivery); webhook.send(delivery);
        assertEquals(delivery.notificationId().toString(),key.get()); assertEquals(2,calls.get());
    }
    @Test void providerFailuresOpenCircuitAndRecoveryAllowsCallsAgain() {
        status.set(503);
        for(int i=0;i<5;i++) assertThrows(RuntimeException.class,()->webhook.send(delivery()));
        assertEquals(CircuitBreaker.State.OPEN,breakers.circuitBreaker("notificationProvider").getState());
        assertThrows(CallNotPermittedException.class,()->webhook.send(delivery())); assertEquals(5,calls.get());
        status.set(204); breakers.circuitBreaker("notificationProvider").transitionToHalfOpenState();
        webhook.send(delivery()); webhook.send(delivery());
        assertEquals(CircuitBreaker.State.CLOSED,breakers.circuitBreaker("notificationProvider").getState());
    }
    @Test void bulkheadRejectsWhenAllPermitsAreOccupied() {
        var bulkhead = bulkheads.bulkhead("notificationProvider");
        for(int i=0;i<4;i++) bulkhead.acquirePermission();
        try { assertThrows(BulkheadFullException.class,()->webhook.send(delivery())); assertEquals(0,calls.get()); }
        finally { for(int i=0;i<4;i++) bulkhead.onComplete(); }
    }
    @Test void stalledProviderHasReadTimeout() {
        delay.set(4000); long start=System.nanoTime();
        assertThrows(RuntimeException.class,()->webhook.send(delivery()));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()<3900);
    }
}
