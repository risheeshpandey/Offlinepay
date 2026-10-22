package com.risheesh.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "eureka.client.enabled=false", webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
    static final AtomicReference<String> internal = new AtomicReference<>();
    static final AtomicReference<String> authorization = new AtomicReference<>();
    static final AtomicReference<String> cookie = new AtomicReference<>();
    static HttpServer core = backend("core");
    static HttpServer notices = backend("notifications");
    @LocalServerPort int port;
    WebTestClient client;
    @BeforeEach void client() { client = WebTestClient.bindToServer().baseUrl("http://localhost:"+port).build(); }
    static HttpServer backend(String name) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/", exchange -> {
                internal.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                if (exchange.getRequestURI().getPath().contains("login"))
                    exchange.getResponseHeaders().add("Set-Cookie", "refreshToken=example; HttpOnly; Path=/api/v1/auth; SameSite=Strict");
                byte[] data = name.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,data.length); exchange.getResponseBody().write(data); exchange.close();
            });
            server.start(); return server;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void urls(DynamicPropertyRegistry r) {
        r.add("offlinepay.core-url", () -> "http://localhost:"+core.getAddress().getPort());
        r.add("offlinepay.notifications-url", () -> "http://localhost:"+notices.getAddress().getPort());
    }
    @AfterAll static void stop() { core.stop(0);notices.stop(0); }
    @Test void routePrecedenceAndBearerPassThrough() {
        client.get().uri("/api/v1/notifications").header("Authorization","Bearer user-token")
            .exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("notifications");
        assertEquals("Bearer user-token",authorization.get());
        client.get().uri("/api/v1/wallet").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("core");
    }
    @Test void cookiesSurviveAndServiceHeaderCannotBeSpoofed() {
        client.post().uri("/api/v1/auth/login").header("Cookie","XSRF-TOKEN=masked")
            .header("X-Service-Token","attacker").header("X-Request-ID","manual-request-001")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Request-ID","manual-request-001")
            .expectHeader().valueMatches("Set-Cookie",".*HttpOnly.*");
        assertNull(internal.get());assertEquals("XSRF-TOKEN=masked",cookie.get());
    }
    @Test void internalEndpointsAreNeverPublicRoutes() {
        client.get().uri("/api/v1/internal/session").exchange().expectStatus().isNotFound();
        client.get().uri("/internal/v1/events/abc").exchange().expectStatus().isNotFound();
    }
}
