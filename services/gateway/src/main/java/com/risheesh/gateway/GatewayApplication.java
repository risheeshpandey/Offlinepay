package com.risheesh.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
    @Bean
    RouteLocator routes(RouteLocatorBuilder builder,
            @Value("${offlinepay.core-url}") String core,
            @Value("${offlinepay.notifications-url}") String notifications) {
        return builder.routes()
                .route("notifications", r -> r.path("/api/v1/notifications", "/api/v1/notifications/**").uri(notifications))
                .route("financial-core", r -> r.path("/api/v1/**").uri(core))
                .build();
    }
}
