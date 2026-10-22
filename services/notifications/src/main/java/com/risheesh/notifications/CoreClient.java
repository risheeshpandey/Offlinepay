package com.risheesh.notifications;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

// Only read operations cross this boundary. No money movement over Feign.
@FeignClient(name = "offlinepay-core", url = "${offlinepay.core-url:}")
public interface CoreClient {
    record EventDigest(UUID eventId, String payloadHash) {}
    record Session(UUID userId) {}
    @GetMapping("/internal/v1/events/{id}")
    EventDigest event(@PathVariable("id") UUID id, @RequestHeader("X-Service-Token") String token);
    @GetMapping("/api/v1/internal/session")
    Session session(@RequestHeader("Authorization") String bearer);
}
