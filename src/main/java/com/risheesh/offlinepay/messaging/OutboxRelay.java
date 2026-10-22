package com.risheesh.offlinepay.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name = "offlinepay.messaging.enabled", havingValue = "true")
public class OutboxRelay {
    private final OutboxTransactions transactions;
    private final PaymentPublisher publisher;
    @org.springframework.beans.factory.annotation.Value("${offlinepay.workers-enabled:true}")
    private boolean workersEnabled;

    @Scheduled(fixedDelayString = "${offlinepay.messaging.poll-ms:2000}")
    public void poll() {
        if (!workersEnabled) return;
        for (var id : transactions.ready()) {
            var claim = transactions.claim(id);
            if (claim.isEmpty()) continue;
            String error = null;
            try { publisher.publish(claim.get()); }
            catch (Exception ex) {
                error = ex.getClass().getSimpleName();
                log.warn("outbox_publish_failed eventId={} type={}", id, error);
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            transactions.finish(claim.get(), error);
            if (Thread.currentThread().isInterrupted()) break;
        }
    }
}
