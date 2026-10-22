package com.risheesh.notifications;

import com.risheesh.offlinepay.messaging.PaymentMessage;
import com.risheesh.offlinepay.notification.NotificationEntity;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.*;

@Service @RequiredArgsConstructor
public class InboxTransaction {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    @PersistenceContext private EntityManager entities;
    @Value("${offlinepay.notifications.webhook-url:}") private String webhookUrl;

    @Transactional
    public void store(PaymentMessage message, String hash) {
        // The unique event ID serializes concurrent deliveries in THIS service's database.
        int inserted = jdbc.update("INSERT INTO consumed_payment_event(event_id,payload_hash,consumed_at) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                message.eventId(), hash, Timestamp.from(clock.instant()));
        if (inserted == 0) return;
        Set<UUID> recipients = new LinkedHashSet<>();
        if (message.senderUserId() != null) recipients.add(message.senderUserId());
        recipients.add(message.receiverUserId());
        for (UUID user : recipients)
            entities.persist(new NotificationEntity(message.eventId(), user, message.kind(), message.amountPaise(),
                    clock.instant(), !webhookUrl.isBlank()));
        // Marker and notifications commit together. A failure rolls both back.
    }
}
