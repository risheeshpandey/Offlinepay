package com.risheesh.offlinepay.ledger;

import com.risheesh.offlinepay.payment.PaymentOperationEntity;
import com.risheesh.offlinepay.wallet.WalletEntity;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class LedgerService {
    private final LedgerEntryRepository ledgerRepository;

    // Never commit a journal separately from its payment and wallet changes.
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordTransfer(PaymentOperationEntity payment, WalletEntity sender, WalletEntity receiver) {
        ledgerRepository.saveAll(List.of(
                entry(payment, sender, AccountKind.WALLET, EntryType.DEBIT),
                entry(payment, receiver, AccountKind.WALLET, EntryType.CREDIT)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDemoTopup(PaymentOperationEntity payment, WalletEntity receiver) {
        ledgerRepository.saveAll(List.of(
                entry(payment, null, AccountKind.DEMO_ISSUANCE, EntryType.DEBIT),
                entry(payment, receiver, AccountKind.WALLET, EntryType.CREDIT)));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> history(UUID walletId, int page, int size) {
        return ledgerRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, PageRequest.of(page, size));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOfflineReservation(PaymentOperationEntity payment, WalletEntity wallet) {
        ledgerRepository.saveAll(List.of(
                entry(payment, wallet, AccountKind.WALLET, EntryType.DEBIT),
                entry(payment, wallet, AccountKind.OFFLINE_RESERVED, EntryType.CREDIT)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOfflineSettlement(PaymentOperationEntity payment, WalletEntity sender, WalletEntity receiver) {
        ledgerRepository.saveAll(List.of(
                entry(payment, sender, AccountKind.OFFLINE_RESERVED, EntryType.DEBIT),
                entry(payment, receiver, AccountKind.WALLET, EntryType.CREDIT)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOfflineRelease(PaymentOperationEntity payment, WalletEntity wallet) {
        ledgerRepository.saveAll(List.of(
                entry(payment, wallet, AccountKind.OFFLINE_RESERVED, EntryType.DEBIT),
                entry(payment, wallet, AccountKind.WALLET, EntryType.CREDIT)));
    }

    private LedgerEntryEntity entry(PaymentOperationEntity payment, WalletEntity wallet,
                                   AccountKind accountKind, EntryType entryType) {
        return LedgerEntryEntity.builder()
                .operation(payment)
                .wallet(wallet)
                .accountKind(accountKind)
                .entryType(entryType)
                .amountPaise(payment.getAmountPaise())
                .createdAt(payment.getCreatedAt())
                .build();
    }
}
