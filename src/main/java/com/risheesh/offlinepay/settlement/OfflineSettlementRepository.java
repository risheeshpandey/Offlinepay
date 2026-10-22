package com.risheesh.offlinepay.settlement;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface OfflineSettlementRepository extends JpaRepository<OfflineSettlementEntity, UUID> {
    boolean existsByAllowanceIdAndSequence(UUID allowanceId, long sequence);

    @Query("select s from OfflineSettlementEntity s where s.paymentId = :id "
            + "and (s.receiverWallet.id = :walletId or s.allowance.wallet.id = :walletId)")
    Optional<OfflineSettlementEntity> findVisible(@Param("id") UUID id, @Param("walletId") UUID walletId);
}
