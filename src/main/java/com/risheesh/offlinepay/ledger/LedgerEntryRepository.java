package com.risheesh.offlinepay.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {
    List<LedgerEntryEntity> findByWalletIdOrderByCreatedAtDescIdDesc(UUID walletId, Pageable pageable);
}
