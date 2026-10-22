package com.risheesh.offlinepay.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@Slf4j
public class PaymentAuditLogger {
    // Commit ke baad hi success log hota hai. Rolled-back payment ka success log nahi.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(PaymentCommittedEvent event) {
        log.info("operation_committed transactionId={} kind={} amountPaise={}",
                event.transactionId(), event.kind(), event.amountPaise());
    }
}
