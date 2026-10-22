package com.risheesh.offlinepay.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperationEntity, UUID> {
    Optional<PaymentOperationEntity> findByActorIdAndIdempotencyKey(UUID actorId, String key);

    @Query("""
            select p from PaymentOperationEntity p
            left join p.senderWallet sender
            where p.id = :paymentId
              and (sender.id = :walletId or p.receiverWallet.id = :walletId)
            """)
    Optional<PaymentOperationEntity> findVisiblePayment(
            @Param("paymentId") UUID paymentId, @Param("walletId") UUID walletId);
}
