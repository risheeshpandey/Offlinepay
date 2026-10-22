package com.risheesh.offlinepay.notification;

import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class NotificationDeliveryTransactions {
    private final NotificationRepository notifications;
    private final Clock clock;
    public record Delivery(UUID notificationId, UUID transactionId, UUID userId, String kind, long amountPaise) {}
    public record Claim(UUID token, Delivery delivery) {}
    @Transactional(readOnly = true)
    public List<UUID> ready() { return notifications.ready(clock.instant(), PageRequest.of(0, 20)); }
    @Transactional
    public Optional<Claim> claim(UUID id) {
        var row = notifications.lock(id).orElseThrow(); var now = clock.instant();
        boolean ready = "PENDING".equals(row.getDeliveryStatus()) && !row.getNextAttemptAt().isAfter(now);
        boolean abandoned = "PROCESSING".equals(row.getDeliveryStatus()) && !row.getLeaseUntil().isAfter(now);
        if (!ready && !abandoned) return Optional.empty();
        UUID token = row.claim(now);
        return Optional.of(new Claim(token, new Delivery(id, row.getEventId(), row.getUserId(), row.getKind(), row.getAmountPaise())));
    }
    @Transactional
    public void finish(Claim claim, String error) {
        var row = notifications.lock(claim.delivery().notificationId()).orElseThrow();
        if (row.owns(claim.token())) row.finish(clock.instant(), error);
    }
}
