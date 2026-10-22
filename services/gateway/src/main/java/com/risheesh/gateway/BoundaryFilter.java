package com.risheesh.gateway;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class BoundaryFilter implements GlobalFilter, Ordered {
    public int getOrder() { return -100; }
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/v1/internal")) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        String supplied = exchange.getRequest().getHeaders().getFirst("X-Request-ID");
        String requestId = supplied != null && supplied.matches("[A-Za-z0-9-]{8,64}") ? supplied : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-ID", requestId);
        var request = exchange.getRequest().mutate().headers(headers -> {
            headers.remove("X-Service-Token");
            headers.remove("X-User-ID");
            headers.remove("Forwarded");
            headers.remove("X-Forwarded-For");
            headers.set("X-Request-ID", requestId);
        }).build();
        // Authentication/ownership remain enforced by each backend, even on direct calls.
        return chain.filter(exchange.mutate().request(request).build());
    }
}
