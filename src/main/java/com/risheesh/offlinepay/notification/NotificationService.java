package com.risheesh.offlinepay.notification;

import com.risheesh.offlinepay.common.*;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notifications;
    private final Clock clock;
    public record Notice(UUID notificationId, UUID transactionId, String kind, BigDecimal amount,
                         String currency, Instant createdAt, Instant readAt) {}

    public List<Notice> list(UUID userId, int page, int size) {
        return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                .stream().map(this::response).toList();
    }
    @Transactional
    public Notice read(UUID userId, UUID id) {
        var notice = notifications.lock(id).filter(n -> n.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found"));
        notice.markRead(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)); return response(notice);
    }
    private Notice response(NotificationEntity notice) {
        return new Notice(notice.getId(), notice.getEventId(), notice.getKind(), Money.toInr(notice.getAmountPaise()),
                "INR", notice.getCreatedAt(), notice.getReadAt());
    }
}
