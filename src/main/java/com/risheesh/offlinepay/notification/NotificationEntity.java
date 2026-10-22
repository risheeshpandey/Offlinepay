package com.risheesh.offlinepay.notification;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "user_notification", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"}))
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationEntity {
    @Id private UUID id;
    @Column(name = "event_id", nullable = false, updatable = false) private UUID eventId;
    @Column(name = "user_id", nullable = false, updatable = false) private UUID userId;
    @Column(nullable = false, length = 30, updatable = false) private String kind;
    @Column(name = "amount_paise", nullable = false, updatable = false) private long amountPaise;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "delivery_status", nullable = false, length = 20) private String deliveryStatus;
    @Column(name = "delivery_attempts", nullable = false) private int deliveryAttempts;
    @Column(name = "next_attempt_at", nullable = false) private Instant nextAttemptAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "lease_token") private UUID leaseToken;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "last_error", length = 100) private String lastError;

    public NotificationEntity(UUID eventId, UUID userId, String kind, long amount, Instant now, boolean webhook) {
        id = UUID.randomUUID(); this.eventId = eventId; this.userId = userId; this.kind = kind;
        amountPaise = amount; createdAt = now; nextAttemptAt = now;
        deliveryStatus = webhook ? "PENDING" : "IN_APP_ONLY";
    }
    public void markRead(Instant now) { if (readAt == null) readAt = now; }
    public UUID claim(Instant now) {
        deliveryStatus = "PROCESSING"; deliveryAttempts++; leaseToken = UUID.randomUUID();
        leaseUntil = now.plusSeconds(60); return leaseToken;
    }
    public boolean owns(UUID token) { return "PROCESSING".equals(deliveryStatus) && token.equals(leaseToken); }
    public void finish(Instant now, String error) {
        leaseUntil = null; leaseToken = null;
        if (error == null) { deliveryStatus = "DELIVERED"; deliveredAt = now; lastError = null; }
        else {
            deliveryStatus = deliveryAttempts >= 8 ? "FAILED" : "PENDING";
            nextAttemptAt = now.plusSeconds(Math.min(300, 1L << Math.min(deliveryAttempts, 8)));
            lastError = error;
        }
    }
}
