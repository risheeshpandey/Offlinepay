package com.risheesh.offlinepay.messaging;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query("select e.id from OutboxEvent e where (e.status = 'PENDING' and e.nextAttemptAt <= :now) or (e.status = 'PROCESSING' and e.leaseUntil <= :now) order by e.createdAt, e.id")
    List<UUID> ready(@Param("now") Instant now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> lock(@Param("id") UUID id);
}
