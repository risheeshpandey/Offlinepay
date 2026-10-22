package com.risheesh.notifications;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "eureka.client.enabled=false", classes = FeignContractTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FeignContractTest {
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,HibernateJpaAutoConfiguration.class})
    @EnableFeignClients(clients = CoreClient.class) @Import(FeignHeaders.class)
    static class Config {}
    static final UUID event = UUID.randomUUID();
    static final UUID user = UUID.randomUUID();
    static final AtomicReference<String> secret = new AtomicReference<>();
    static final AtomicReference<String> bearer = new AtomicReference<>();
    static final AtomicReference<String> requestId = new AtomicReference<>();
    static HttpServer server = start();
    static HttpServer start() {
        try {
            var s = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            s.createContext("/internal/v1/events/", e -> {
                secret.set(e.getRequestHeaders().getFirst("X-Service-Token"));
                requestId.set(e.getRequestHeaders().getFirst("X-Request-ID"));
                byte[] body = ("{\"eventId\":\""+event+"\",\"payloadHash\":\""+"a".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type","application/json");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            s.createContext("/api/v1/internal/session", e -> {
                bearer.set(e.getRequestHeaders().getFirst("Authorization"));
                byte[] body = ("{\"userId\":\""+user+"\"}").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().add("Content-Type","application/json");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });s.start();return s;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("offlinepay.core-url", () -> "http://localhost:"+server.getAddress().getPort());
    }
    @Autowired CoreClient client;
    @AfterAll static void stop() { server.stop(0); }
    @Test void realHttpCarriesOnlyExplicitCredentialsAndRequestId() {
        MDC.put("requestId","feign-contract-001");
        try {
            assertEquals(event,client.event(event,"test-service-token").eventId());
            assertEquals("test-service-token",secret.get());assertEquals("feign-contract-001",requestId.get());
            assertEquals(user,client.session("Bearer example-access-token").userId());
            assertEquals("Bearer example-access-token",bearer.get());
        } finally { MDC.clear(); }
    }
}
