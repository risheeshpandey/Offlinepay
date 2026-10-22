package com.risheesh.offlinepay.messaging;

import com.risheesh.offlinepay.common.PaymentCommittedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component @RequiredArgsConstructor
public class PaymentMetrics {
    private final MeterRegistry meters;
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(PaymentCommittedEvent event) {
        // Kind is a bounded enum-like value. IDs, users and amounts are never metric labels.
        meters.counter("offlinepay.payment.committed", "kind", event.kind()).increment();
    }
}
