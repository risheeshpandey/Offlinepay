package com.risheesh.offlinepay.common;

import java.util.UUID;

public record PaymentCommittedEvent(UUID transactionId, String kind, long amountPaise) {}
