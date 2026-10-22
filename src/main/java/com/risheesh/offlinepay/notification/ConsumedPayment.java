package com.risheesh.offlinepay.notification;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "consumed_payment_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ConsumedPayment {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @Column(name = "consumed_at", nullable = false) private Instant consumedAt;
}
