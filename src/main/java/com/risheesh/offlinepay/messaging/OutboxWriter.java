package com.risheesh.offlinepay.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.*;
import com.risheesh.offlinepay.payment.PaymentOperationRepository;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

@Component @RequiredArgsConstructor
public class OutboxWriter {
    private final PaymentOperationRepository payments;
    private final ObjectMapper json;
    @PersistenceContext private EntityManager entityManager;

    // Synchronous and inside the money transaction, NOT AFTER_COMMIT.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PaymentCommittedEvent event) {
        var operation = payments.findById(event.transactionId()).orElseThrow();
        var message = new PaymentMessage(1, operation.getId(), operation.getId(), operation.getKind().name(),
                operation.getSenderWallet() == null ? null : operation.getSenderWallet().getUser().getId(),
                operation.getReceiverWallet().getUser().getId(), operation.getAmountPaise(), operation.getCreatedAt());
        try {
            String payload = json.writeValueAsString(message);
            entityManager.persist(new OutboxEvent(operation.getId(), payload, Idempotency.fingerprint(payload), operation.getCreatedAt()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot record payment event", ex);
        }
    }
}
