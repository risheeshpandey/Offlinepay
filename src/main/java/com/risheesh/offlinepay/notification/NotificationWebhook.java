package com.risheesh.offlinepay.notification;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class NotificationWebhook {
    private final RestClient client;
    private final String url;
    private final String token;
    public NotificationWebhook(@Value("${offlinepay.notifications.webhook-url:}") String url,
            @Value("${offlinepay.notifications.webhook-token:}") String token, RestClient.Builder builder) {
        this.url = url; this.token = token;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); factory.setReadTimeout(3000);
        client = builder.requestFactory(factory).build();
    }
    // The provider must honor Idempotency-Key: a timeout can happen after it accepted the message.
    @CircuitBreaker(name = "notificationProvider")
    @Bulkhead(name = "notificationProvider")
    public void send(NotificationDeliveryTransactions.Delivery delivery) {
        if (url.isBlank()) throw new IllegalStateException("Notification webhook is not configured");
        var request = client.post().uri(url).header("Idempotency-Key", delivery.notificationId().toString());
        if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
        request.body(delivery).retrieve().toBodilessEntity();
    }
}
