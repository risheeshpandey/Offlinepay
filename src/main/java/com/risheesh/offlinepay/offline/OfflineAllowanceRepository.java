package com.risheesh.offlinepay.offline;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface OfflineAllowanceRepository extends JpaRepository<OfflineAllowanceEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OfflineAllowanceEntity a where a.id = :id")
    Optional<OfflineAllowanceEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<OfflineAllowanceEntity> findByIdAndUserId(UUID id, UUID userId);
    Optional<OfflineAllowanceEntity> findByOperationId(UUID operationId);
    List<OfflineAllowanceEntity> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);
}
