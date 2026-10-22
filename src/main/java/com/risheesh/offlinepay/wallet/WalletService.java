package com.risheesh.offlinepay.wallet;

import com.risheesh.offlinepay.common.*;
import com.risheesh.offlinepay.ledger.LedgerService;
import com.risheesh.offlinepay.payment.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WalletService {
    private final WalletRepository walletRepository;
    private final PaymentOperationRepository paymentRepository;
    private final LedgerService ledgerService;
    private final ApplicationEventPublisher events;

    @Value("${offlinepay.demo-topups-enabled}")
    private boolean demoTopupsEnabled;

    public record Balance(UUID walletId, BigDecimal availableBalance, String currency,
                          BigDecimal offlineReservedBalance, BigDecimal totalBalance) {}
    public record Payment(UUID transactionId, String kind, UUID senderWalletId, UUID receiverWalletId,
                          BigDecimal amount, String currency, String status, Instant createdAt) {}
    public record Entry(UUID entryId, UUID transactionId, String entryType, BigDecimal amount,
                        Instant createdAt, String accountKind) {}
    private record LockedWallets(WalletEntity sender, WalletEntity receiver) {}

    public Balance balance(UUID userId) {
        WalletEntity wallet = walletRepository.findByUserId(userId).orElseThrow(this::walletNotFound);
        return new Balance(wallet.getId(), Money.toInr(wallet.getAvailablePaise()), "INR",
                Money.toInr(wallet.getOfflineReservedPaise()),
                Money.toInr(Math.addExact(wallet.getAvailablePaise(), wallet.getOfflineReservedPaise())));
    }

    @Transactional
    public Payment transfer(UUID userId, UUID receiverId, BigDecimal amount, String key) {
        Idempotency.validateKey(key);
        long paise = Money.toPaise(amount);
        UUID senderId = ownWalletId(userId);
        if (senderId.equals(receiverId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER", "Choose another wallet");
        }

        String hash = Idempotency.fingerprint("TRANSFER|" + senderId + "|" + receiverId + "|" + paise);
        LockedWallets wallets = lockInOrder(senderId, receiverId);
        Payment previous = findPreviousPayment(userId, key, hash);
        if (previous != null) return previous;

        wallets.sender().debit(paise);
        wallets.receiver().credit(paise);
        // Hibernate dirty checking persists both locked wallets at commit.
        PaymentOperationEntity payment = createPayment(
                wallets.sender(), wallets.receiver(), PaymentKind.TRANSFER, paise, key, hash);
        ledgerService.recordTransfer(payment, wallets.sender(), wallets.receiver());
        return toResponse(payment);
    }

    @Transactional
    public Payment demoTopup(UUID userId, BigDecimal amount, String key) {
        if (!demoTopupsEnabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEMO_DISABLED", "Demo funding is disabled");
        }
        Idempotency.validateKey(key);
        long paise = Money.toPaise(amount);
        WalletEntity wallet = lockWallet(ownWalletId(userId));
        String hash = Idempotency.fingerprint("DEMO_TOPUP|" + wallet.getId() + "|" + paise);
        Payment previous = findPreviousPayment(userId, key, hash);
        if (previous != null) return previous;

        wallet.credit(paise);
        PaymentOperationEntity payment = createPayment(null, wallet, PaymentKind.DEMO_TOPUP, paise, key, hash);
        ledgerService.recordDemoTopup(payment, wallet);
        return toResponse(payment);
    }

    public Payment payment(UUID userId, UUID transactionId) {
        return paymentRepository.findVisiblePayment(transactionId, ownWalletId(userId))
                .map(this::toResponse)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
    }

    public List<Entry> ledger(UUID userId, int page, int size) {
        return ledgerService.history(ownWalletId(userId), page, size).stream()
                .map(entry -> new Entry(entry.getId(), entry.getOperation().getId(), entry.getEntryType().name(),
                        Money.toInr(entry.getAmountPaise()), entry.getCreatedAt(), entry.getAccountKind().name()))
                .toList();
    }

    private UUID ownWalletId(UUID userId) {
        return walletRepository.findWalletIdByUserId(userId).orElseThrow(this::walletNotFound);
    }

    private WalletEntity lockWallet(UUID walletId) {
        return walletRepository.findByIdForUpdate(walletId).orElseThrow(this::walletNotFound);
    }

    private LockedWallets lockInOrder(UUID senderId, UUID receiverId) {
        // Both directions acquire the same first lock, avoiding A->B / B->A deadlocks.
        boolean senderFirst = senderId.toString().compareTo(receiverId.toString()) < 0;
        WalletEntity first = lockWallet(senderFirst ? senderId : receiverId);
        WalletEntity second = lockWallet(senderFirst ? receiverId : senderId);
        return senderFirst ? new LockedWallets(first, second) : new LockedWallets(second, first);
    }

    private Payment findPreviousPayment(UUID userId, String key, String hash) {
        // The actor wallet is already locked; concurrent retries wait for its commit.
        Optional<PaymentOperationEntity> previous = paymentRepository.findByActorIdAndIdempotencyKey(userId, key);
        if (previous.isEmpty()) return null;
        if (!previous.get().getRequestHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key was used for different request data");
        }
        log.info("payment_replay transactionId={}", previous.get().getId());
        return toResponse(previous.get());
    }

    private PaymentOperationEntity createPayment(WalletEntity sender, WalletEntity receiver, PaymentKind kind,
                                                 long paise, String key, String hash) {
        WalletEntity actorWallet = sender == null ? receiver : sender;
        PaymentOperationEntity payment = paymentRepository.save(PaymentOperationEntity.builder()
                .actor(actorWallet.getUser())
                .idempotencyKey(key)
                .requestHash(hash)
                .kind(kind)
                .senderWallet(sender)
                .receiverWallet(receiver)
                .amountPaise(paise)
                .createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .build());
        events.publishEvent(new PaymentCommittedEvent(payment.getId(), kind.name(), paise));
        return payment;
    }

    private Payment toResponse(PaymentOperationEntity payment) {
        UUID senderId = payment.getSenderWallet() == null ? null : payment.getSenderWallet().getId();
        return new Payment(payment.getId(), payment.getKind().name(), senderId, payment.getReceiverWallet().getId(),
                Money.toInr(payment.getAmountPaise()), "INR", "SUCCESS", payment.getCreatedAt());
    }

    private ApiException walletNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found");
    }
}
