package com.risheesh.offlinepay.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.Idempotency;
import com.risheesh.offlinepay.messaging.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class NotificationInbox {
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final Clock clock;
    @Value("${offlinepay.notifications.webhook-url:}") private String webhookUrl;
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public void accept(String key, String payload) {
        PaymentMessage message;
        try { message = json.readValue(payload, PaymentMessage.class); }
        catch (JsonProcessingException | IllegalArgumentException ex) { throw new InvalidPaymentMessage(); }
        if (message == null || message.version() != 1 || message.eventId() == null
                || !message.eventId().equals(message.transactionId()) || !message.eventId().toString().equals(key))
            throw new InvalidPaymentMessage();
        // Same database in this milestone: verify against the authoritative outbox and serialize duplicates.
        var source = outbox.lock(message.eventId()).orElseThrow(InvalidPaymentMessage::new);
        if (!source.getPayloadHash().equals(Idempotency.fingerprint(payload))) throw new InvalidPaymentMessage();
        if (entityManager.find(ConsumedPayment.class, message.eventId()) != null) return;
        Set<UUID> recipients = new LinkedHashSet<>();
        if (message.senderUserId() != null) recipients.add(message.senderUserId());
        recipients.add(message.receiverUserId());
        for (UUID userId : recipients) {
            entityManager.persist(new NotificationEntity(message.eventId(), userId, message.kind(),
                    message.amountPaise(), clock.instant(), !webhookUrl.isBlank()));
        }
        entityManager.persist(new ConsumedPayment(message.eventId(), source.getPayloadHash(), clock.instant()));
    }
}
