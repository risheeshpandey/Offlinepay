package com.risheesh.notifications;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.Idempotency;
import com.risheesh.offlinepay.messaging.*;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class RemoteInbox {
    private final CoreClient core;
    private final ObjectMapper json;
    private final InboxTransaction transaction;
    @Value("${offlinepay.internal-token}") private String token;

    // Network calls finish before the local database transaction starts.
    public void accept(String key, String payload) {
        PaymentMessage message;
        try { message = json.readValue(payload, PaymentMessage.class); }
        catch (JsonProcessingException | IllegalArgumentException ex) { throw new InvalidPaymentMessage(); }
        if (message == null || message.version() != 1 || message.eventId() == null
                || !message.eventId().equals(message.transactionId()) || !message.eventId().toString().equals(key)
                || message.receiverUserId() == null || message.amountPaise() <= 0 || message.occurredAt() == null)
            throw new InvalidPaymentMessage();
        String hash = Idempotency.fingerprint(payload);
        CoreClient.EventDigest source;
        try { source = core.event(message.eventId(), token); }
        catch (FeignException.NotFound ex) { throw new InvalidPaymentMessage(); }
        // Bad service credentials, timeouts and outages remain retryable. No false success.
        if (source == null || !message.eventId().equals(source.eventId()) || !hash.equals(source.payloadHash()))
            throw new InvalidPaymentMessage();
        transaction.store(message, hash);
    }
}
