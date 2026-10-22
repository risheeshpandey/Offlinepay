package com.risheesh.offlinepay.notification;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {
    List<NotificationEntity> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NotificationEntity n where n.id=:id")
    Optional<NotificationEntity> lock(@Param("id") UUID id);
    @Query("select n.id from NotificationEntity n where (n.deliveryStatus='PENDING' and n.nextAttemptAt<=:now) or (n.deliveryStatus='PROCESSING' and n.leaseUntil<=:now) order by n.createdAt, n.id")
    List<UUID> ready(@Param("now") Instant now, Pageable page);
}
