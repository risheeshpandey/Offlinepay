package com.risheesh.offlinepay.messaging;

import java.time.Instant;
import java.util.UUID;

// Versioned integration contract. No credentials, phone numbers, or private keys.
public record PaymentMessage(int version, UUID eventId, UUID transactionId, String kind,
        UUID senderUserId, UUID receiverUserId, long amountPaise, Instant occurredAt) {}
