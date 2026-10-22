package com.risheesh.offlinepay.messaging;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "outbox_event") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id private UUID id;
    @Column(name = "operation_id", nullable = false, unique = true) private UUID operationId;
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false) private String payload;
    @Column(name = "payload_hash", nullable = false, length = 64, updatable = false) private String payloadHash;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false) private int attempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "lease_token") private UUID leaseToken;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "last_error", length = 100) private String lastError;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    public OutboxEvent(UUID id, String payload, String hash, Instant now) {
        this.id = id; this.operationId = id; this.payload = payload; this.payloadHash = hash;
        this.status = "PENDING"; this.nextAttemptAt = now; this.createdAt = now;
    }
    public UUID claim(Instant now) {
        status = "PROCESSING"; attempts++; leaseToken = UUID.randomUUID();
        leaseUntil = now.plusSeconds(60); return leaseToken;
    }
    public boolean owns(UUID token) { return "PROCESSING".equals(status) && token.equals(leaseToken); }
    public void published(Instant now) {
        status = "PUBLISHED"; publishedAt = now; leaseUntil = null; leaseToken = null; lastError = null;
    }
    public void failed(Instant now, String error) {
        status = attempts >= 8 ? "FAILED" : "PENDING";
        nextAttemptAt = now.plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)));
        lastError = error; leaseUntil = null; leaseToken = null;
    }
}
