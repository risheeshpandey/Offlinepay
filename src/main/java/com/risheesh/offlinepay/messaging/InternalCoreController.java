package com.risheesh.offlinepay.messaging;

import com.risheesh.offlinepay.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

// This read-only contract replaces cross-service database access.
@RestController
@ConditionalOnProperty(name = "offlinepay.notifications.local-enabled", havingValue = "false")
public class InternalCoreController {
    private final OutboxRepository outbox;
    private final byte[] serviceToken;
    public InternalCoreController(OutboxRepository outbox, @Value("${offlinepay.internal-token}") String token) {
        if (token.length() < 32) throw new IllegalArgumentException("Use a random internal service token of at least 32 characters");
        this.outbox = outbox;
        this.serviceToken = token.getBytes(StandardCharsets.UTF_8);
    }
    public record EventDigest(UUID eventId, String payloadHash) {}
    public record Session(UUID userId) {}

    @GetMapping("/internal/v1/events/{id}")
    public EventDigest event(@PathVariable UUID id,
            @RequestHeader(value = "X-Service-Token", defaultValue = "") String supplied) {
        if (!MessageDigest.isEqual(serviceToken, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SERVICE_CREDENTIAL", "Service authentication required");
        var event = outbox.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", "Event not found"));
        return new EventDigest(event.getId(), event.getPayloadHash());
    }
    @GetMapping("/api/v1/internal/session")
    public Session session(@AuthenticationPrincipal Jwt jwt) {
        return new Session(UUID.fromString(jwt.getSubject()));
    }
}
