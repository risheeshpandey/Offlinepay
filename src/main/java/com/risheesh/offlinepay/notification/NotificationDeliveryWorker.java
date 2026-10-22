package com.risheesh.offlinepay.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor @Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "offlinepay.notifications.local-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDeliveryWorker {
    private final NotificationDeliveryTransactions transactions;
    private final NotificationWebhook webhook;
    @Value("${offlinepay.workers-enabled:true}") private boolean workersEnabled;
    @Scheduled(fixedDelayString = "${offlinepay.notifications.poll-ms:3000}")
    public void poll() {
        if (!workersEnabled) return;
        for (var id : transactions.ready()) {
            var claim = transactions.claim(id);
            if (claim.isEmpty()) continue;
            String error = null;
            try { webhook.send(claim.get().delivery()); }
            catch (RuntimeException ex) {
                error = ex.getClass().getSimpleName();
                log.warn("notification_delivery_failed notificationId={} type={}", id, error);
            }
            transactions.finish(claim.get(), error);
        }
    }
}
