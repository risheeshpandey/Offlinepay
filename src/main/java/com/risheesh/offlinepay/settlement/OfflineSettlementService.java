package com.risheesh.offlinepay.settlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.risheesh.offlinepay.common.*;
import com.risheesh.offlinepay.device.DeviceService;
import com.risheesh.offlinepay.ledger.LedgerService;
import com.risheesh.offlinepay.offline.*;
import com.risheesh.offlinepay.payment.*;
import com.risheesh.offlinepay.protocol.*;
import com.risheesh.offlinepay.wallet.*;
import jakarta.persistence.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OfflineSettlementService {
    private final WalletRepository wallets;
    private final OfflineAllowanceRepository allowances;
    private final OfflineSettlementRepository settlements;
    private final PaymentOperationRepository payments;
    private final DeviceService devices;
    private final ServerSigningService signer;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    @PersistenceContext private EntityManager entityManager;

    public record Settlement(UUID paymentId, UUID transactionId, UUID allowanceId,
            UUID senderWalletId, UUID receiverWalletId, BigDecimal amount, String currency,
            long sequence, String status, Instant settledAt) {}
    public record Release(UUID allowanceId, UUID transactionId, BigDecimal releasedAmount,
                          String currency, String status, Instant releasedAt) {}
    private record LockedWallets(WalletEntity sender, WalletEntity receiver) {}

    /** Receiver submits one immutable proof; paymentId is the durable retry identity. */
    @Transactional
    public Settlement sync(UUID receiverUserId, PaymentProof proof) {
        UUID receiverId = ownWalletId(receiverUserId);
        try { OfflineProofVerifier.verifyForSettlement(proof, signer.publicKey(), receiverId, clock.instant()); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(BAD_REQUEST, "INVALID_OFFLINE_PROOF", "Check proof signatures, recipient, amount and signed time");
        }
        AllowanceClaims certificate = proof.allowance().claims();
        PaymentClaims payment = proof.payment();
        String hash = Idempotency.fingerprint(canonical(proof.allowance(), payment) + "\n" + proof.signature());

        // All money flows lock wallet UUID strings in the same order; allowance lock is always last.
        LockedWallets locked = lockInOrder(certificate.walletId(), receiverId);
        OfflineAllowanceEntity allowance = allowances.findByIdForUpdate(certificate.allowanceId())
                .orElseThrow(() -> new ApiException(NOT_FOUND, "ALLOWANCE_NOT_FOUND", "Allowance not found"));
        if (!AllowanceCertificates.claims(allowance, signer.publicKey().issuer()).equals(certificate)
                || !Objects.equals(allowance.getServerSignature(), proof.allowance().signature()))
            throw new ApiException(CONFLICT, "CERTIFICATE_MISMATCH", "Certificate does not match the stored reservation");

        Optional<OfflineSettlementEntity> previous = settlements.findById(payment.paymentId());
        if (previous.isPresent()) {
            if (!previous.get().getProofHash().equals(hash))
                throw new ApiException(CONFLICT, "PAYMENT_ID_CONFLICT", "Payment ID was used for another proof");
            log.info("offline_settlement_replay paymentId={}", payment.paymentId());
            // A committed retry remains readable after expiry/release/revocation. No new money moves.
            return response(previous.get());
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (!now.isBefore(allowance.getSyncDeadline()))
            throw new ApiException(CONFLICT, "SYNC_DEADLINE_PASSED", "New claims are closed after the sync deadline");
        devices.requireActive(allowance.getUser().getId(), allowance.getDeviceId());
        if (settlements.existsByAllowanceIdAndSequence(allowance.getId(), payment.sequence()))
            throw new ApiException(CONFLICT, "SEQUENCE_CONFLICT", "Another payment already consumed this allowance sequence");

        allowance.settle(payment.amountPaise());
        locked.sender().consumeOffline(payment.amountPaise());
        locked.receiver().credit(payment.amountPaise());
        PaymentOperationEntity operation = operation(locked.sender(), locked.receiver(), PaymentKind.OFFLINE_SETTLE,
                payment.amountPaise(), "sys:offline-settle:" + payment.paymentId(), hash, now);
        ledger.recordOfflineSettlement(operation, locked.sender(), locked.receiver());
        OfflineSettlementEntity settlement = OfflineSettlementEntity.builder().paymentId(payment.paymentId())
                .allowance(allowance).operation(operation).receiverWallet(locked.receiver())
                .sequence(payment.sequence()).proofHash(hash).proofJson(encode(proof)).settledAt(now).build();
        // Persist, not merge: a caller-supplied paymentId must INSERT or fail uniqueness, never overwrite.
        entityManager.persist(settlement);
        committed(operation);
        return response(settlement);
    }

    public Settlement get(UUID userId, UUID paymentId) {
        return settlements.findVisible(paymentId, ownWalletId(userId)).map(this::response)
                .orElseThrow(() -> new ApiException(NOT_FOUND, "SETTLEMENT_NOT_FOUND", "Settlement not found"));
    }

    /** Explicit owner release only after all new claims are closed. Replay returns the stored release. */
    @Transactional
    public Release release(UUID userId, UUID allowanceId) {
        WalletEntity wallet = lockWallet(ownWalletId(userId));
        OfflineAllowanceEntity allowance = allowances.findByIdForUpdate(allowanceId)
                .filter(a -> a.getWallet().getId().equals(wallet.getId()))
                .orElseThrow(() -> new ApiException(NOT_FOUND, "ALLOWANCE_NOT_FOUND", "Allowance not found"));
        if (allowance.getReleaseOperation() != null) return releaseResponse(allowance);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (now.isBefore(allowance.getSyncDeadline()))
            throw new ApiException(CONFLICT, "SYNC_WINDOW_OPEN", "Funds stay reserved until the sync deadline");
        long unused = allowance.remainingPaise();
        if (unused == 0) return new Release(allowanceId, null, Money.toInr(0), "INR", "SETTLED", null);
        wallet.releaseOffline(unused);
        String hash = Idempotency.fingerprint("OFFLINE_RELEASE|" + allowanceId + "|" + unused);
        PaymentOperationEntity operation = operation(wallet, wallet, PaymentKind.OFFLINE_RELEASE, unused,
                "sys:offline-release:" + allowanceId, hash, now);
        allowance.release(operation, now);
        ledger.recordOfflineRelease(operation, wallet);
        committed(operation);
        return releaseResponse(allowance);
    }

    private UUID ownWalletId(UUID userId) {
        return wallets.findWalletIdByUserId(userId)
                .orElseThrow(() -> new ApiException(NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
    }
    private WalletEntity lockWallet(UUID id) {
        return wallets.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found"));
    }
    private LockedWallets lockInOrder(UUID senderId, UUID receiverId) {
        boolean senderFirst = senderId.toString().compareTo(receiverId.toString()) < 0;
        WalletEntity first = lockWallet(senderFirst ? senderId : receiverId);
        WalletEntity second = lockWallet(senderFirst ? receiverId : senderId);
        return senderFirst ? new LockedWallets(first, second) : new LockedWallets(second, first);
    }
    private PaymentOperationEntity operation(WalletEntity sender, WalletEntity receiver, PaymentKind kind,
                                             long paise, String key, String hash, Instant now) {
        return payments.save(PaymentOperationEntity.builder().actor(sender.getUser()).senderWallet(sender)
                .receiverWallet(receiver).kind(kind).amountPaise(paise).idempotencyKey(key)
                .requestHash(hash).createdAt(now).build());
    }
    private String encode(PaymentProof proof) {
        try { return json.writeValueAsString(proof); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Cannot store verified proof", ex); }
    }
    private void committed(PaymentOperationEntity operation) {
        events.publishEvent(new PaymentCommittedEvent(operation.getId(), operation.getKind().name(), operation.getAmountPaise()));
    }
    private Settlement response(OfflineSettlementEntity settlement) {
        PaymentOperationEntity operation = settlement.getOperation();
        return new Settlement(settlement.getPaymentId(), operation.getId(), settlement.getAllowance().getId(),
                operation.getSenderWallet().getId(), operation.getReceiverWallet().getId(),
                Money.toInr(operation.getAmountPaise()), "INR", settlement.getSequence(), "SETTLED", settlement.getSettledAt());
    }
    private Release releaseResponse(OfflineAllowanceEntity allowance) {
        return new Release(allowance.getId(), allowance.getReleaseOperation().getId(),
                Money.toInr(allowance.getReleasedPaise()), "INR", "RELEASED", allowance.getReleasedAt());
    }
}
