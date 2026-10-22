package com.risheesh.offlinepay.offline;

import com.risheesh.offlinepay.auth.UserEntity;
import com.risheesh.offlinepay.common.ApiException;
import static org.springframework.http.HttpStatus.CONFLICT;
import com.risheesh.offlinepay.payment.PaymentOperationEntity;
import com.risheesh.offlinepay.wallet.WalletEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_allowance")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OfflineAllowanceEntity {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private WalletEntity wallet;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_id", nullable = false, unique = true, updatable = false)
    private PaymentOperationEntity operation;

    @Column(name = "device_id", nullable = false, length = 80, updatable = false)
    private String deviceId;

    // Nullable only for pre-V5 reservations. A legacy allowance is never silently rebound to a new key.
    @Column(name = "device_public_key", length = 100, updatable = false)
    private String devicePublicKey;
    @Column(name = "server_key_id", length = 64, updatable = false)
    private String serverKeyId;
    @Column(name = "server_signature", length = 100)
    private String serverSignature;

    public void attachSignature(String signature) {
        if (serverSignature != null) throw new IllegalStateException("Allowance already signed");
        serverSignature = signature;
    }

    @Column(name = "allocated_paise", nullable = false, updatable = false)
    private long allocatedPaise;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AllowanceStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "settled_paise", nullable = false)
    private long settledPaise;
    @Column(name = "released_paise", nullable = false)
    private long releasedPaise;
    @Column(name = "sync_deadline", nullable = false, updatable = false)
    private Instant syncDeadline;
    @Column(name = "released_at")
    private Instant releasedAt;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "release_operation_id", unique = true)
    private PaymentOperationEntity releaseOperation;

    public long remainingPaise() { return allocatedPaise - settledPaise - releasedPaise; }

    public void settle(long paise) {
        if (status == AllowanceStatus.RELEASED)
            throw new ApiException(CONFLICT,
                    "ALLOWANCE_RELEASED", "Unused allowance has already been released");
        if (paise <= 0 || paise > remainingPaise())
            throw new ApiException(CONFLICT,
                    "ALLOWANCE_EXHAUSTED", "Payment exceeds the remaining allowance");
        settledPaise += paise;
        status = remainingPaise() == 0 ? AllowanceStatus.SETTLED : AllowanceStatus.PARTIALLY_SETTLED;
    }

    public void release(PaymentOperationEntity operation, Instant now) {
        if (remainingPaise() <= 0 || releaseOperation != null) throw new IllegalStateException("Nothing to release");
        releasedPaise = remainingPaise();
        releaseOperation = operation;
        releasedAt = now;
        status = AllowanceStatus.RELEASED;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
