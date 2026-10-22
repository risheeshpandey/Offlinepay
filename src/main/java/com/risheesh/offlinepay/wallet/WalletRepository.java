package com.risheesh.offlinepay.wallet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<WalletEntity, UUID> {
    Optional<WalletEntity> findByUserId(UUID userId);

    // Read only the ID before locking; do not cache a potentially stale balance.
    @Query("select w.id from WalletEntity w where w.user.id = :userId")
    Optional<UUID> findWalletIdByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletEntity w where w.id = :walletId")
    Optional<WalletEntity> findByIdForUpdate(@Param("walletId") UUID walletId);
}
