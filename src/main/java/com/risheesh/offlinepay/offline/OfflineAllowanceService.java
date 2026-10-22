package com.risheesh.offlinepay.offline;

import com.risheesh.offlinepay.common.*;
import com.risheesh.offlinepay.device.DeviceService;
import com.risheesh.offlinepay.protocol.*;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import com.risheesh.offlinepay.ledger.LedgerService;
import com.risheesh.offlinepay.payment.*;
import com.risheesh.offlinepay.wallet.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class OfflineAllowanceService {
    private final WalletRepository wallets;
    private final PaymentOperationRepository payments;
    private final OfflineAllowanceRepository allowances;
    private final LedgerService ledger;
    private final ApplicationEventPublisher events;
    private final DeviceService devices;
    private final ServerSigningService signer;
    private final Clock clock;

    @Value("${offlinepay.offline-allowance-lifetime}")
    private Duration allowanceLifetime;

    @Value("${offlinepay.offline-sync-grace}")
    private Duration syncGrace;

    public record Allowance(UUID allowanceId, UUID transactionId, UUID walletId, String deviceId,
                            BigDecimal allocatedAmount, String currency, String status, Instant expiresAt,
                            Instant syncDeadline, BigDecimal settledAmount, BigDecimal releasedAmount,
                            BigDecimal remainingAmount) {}

    @PostConstruct
    void validateLifetime() {
        if (syncGrace.isNegative() || syncGrace.isZero())
            throw new IllegalArgumentException("Sync grace must be positive");
        if (allowanceLifetime.isNegative() || allowanceLifetime.isZero())
            throw new IllegalArgumentException("Allowance lifetime must be positive");
    }

    @Transactional
    public Allowance reserve(UUID userId, BigDecimal amount, String deviceId, String key) {
        Idempotency.validateKey(key);
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._:-]{1,80}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_ID", "Check device ID format");
        }
        long paise = Money.toPaise(amount);
        UUID walletId = wallets.findWalletIdByUserId(userId).orElseThrow(this::walletNotFound);
        WalletEntity wallet = wallets.findByIdForUpdate(walletId).orElseThrow(this::walletNotFound);
        String hash = Idempotency.fingerprint("OFFLINE_RESERVE|" + walletId + "|" + deviceId + "|" + paise);

        Optional<PaymentOperationEntity> previous = payments.findByActorIdAndIdempotencyKey(userId, key);
        if (previous.isPresent()) {
            if (!previous.get().getRequestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Key was used for different request data");
            }
            log.info("reservation_replay transactionId={}", previous.get().getId());
            return response(allowances.findByOperationId(previous.get().getId()).orElseThrow());
        }

        // Available se reserved bucket mein money move hoti hai; total same rehta hai.
        var device = devices.requireActive(userId, deviceId);
        wallet.reserveOffline(paise);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        PaymentOperationEntity payment = payments.save(PaymentOperationEntity.builder()
                .actor(wallet.getUser()).idempotencyKey(key).requestHash(hash)
                .kind(PaymentKind.OFFLINE_RESERVE).senderWallet(wallet).receiverWallet(wallet)
                .amountPaise(paise).createdAt(now).build());
        OfflineAllowanceEntity allowance = OfflineAllowanceEntity.builder().id(UUID.randomUUID())
                .user(wallet.getUser()).wallet(wallet).operation(payment).deviceId(deviceId)
                .devicePublicKey(device.getPublicKey()).serverKeyId(signer.publicKey().keyId())
                .allocatedPaise(paise).status(AllowanceStatus.RESERVED)
                .createdAt(now).expiresAt(now.plus(allowanceLifetime))
                .syncDeadline(now.plus(allowanceLifetime).plus(syncGrace)).build();
        allowance.attachSignature(signer.sign(claims(allowance)));
        allowance = allowances.save(allowance);
        ledger.recordOfflineReservation(payment, wallet);
        events.publishEvent(new PaymentCommittedEvent(payment.getId(), payment.getKind().name(), paise));
        return response(allowance);
    }

    public Allowance get(UUID userId, UUID allowanceId) {
        return allowances.findByIdAndUserId(allowanceId, userId).map(this::response)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ALLOWANCE_NOT_FOUND", "Allowance not found"));
    }

    public List<Allowance> list(UUID userId, int page, int size) {
        return allowances.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
                .stream().map(this::response).toList();
    }

    public SignedAllowance certificate(UUID userId, UUID allowanceId) {
        OfflineAllowanceEntity allowance = allowances.findByIdAndUserId(allowanceId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ALLOWANCE_NOT_FOUND", "Allowance not found"));
        if (allowance.getServerSignature() == null)
            throw new ApiException(HttpStatus.CONFLICT, "LEGACY_ALLOWANCE_UNSIGNED", "Pre-V5 reservation has no certificate; funds remain reserved");
        devices.requireActive(userId, allowance.getDeviceId());
        if (allowance.remainingPaise() == 0)
            throw new ApiException(HttpStatus.CONFLICT, "ALLOWANCE_CLOSED", "Allowance has no remaining funds");
        SignedAllowance certificate = new SignedAllowance(claims(allowance), allowance.getServerSignature());
        try { OfflineProofVerifier.verifyAllowance(certificate, signer.publicKey(), clock.instant()); }
        catch (IllegalArgumentException ex) { throw new ApiException(HttpStatus.CONFLICT, "ALLOWANCE_UNAVAILABLE", "Allowance expired or signing key changed"); }
        return certificate;
    }

    private AllowanceClaims claims(OfflineAllowanceEntity a) {
        return AllowanceCertificates.claims(a, signer.publicKey().issuer());
    }

    private Allowance response(OfflineAllowanceEntity allowance) {
        return new Allowance(allowance.getId(), allowance.getOperation().getId(), allowance.getWallet().getId(),
                allowance.getDeviceId(), Money.toInr(allowance.getAllocatedPaise()), "INR",
                allowance.getStatus().name(), allowance.getExpiresAt(), allowance.getSyncDeadline(),
                Money.toInr(allowance.getSettledPaise()), Money.toInr(allowance.getReleasedPaise()),
                Money.toInr(allowance.remainingPaise()));
    }

    private ApiException walletNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Wallet not found");
    }
}
