package com.risheesh.offlinepay.wallet;

import com.risheesh.offlinepay.auth.UserEntity;
import com.risheesh.offlinepay.common.ApiException;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WalletEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true, updatable = false)
    private UserEntity user;

    @Column(name = "available_paise", nullable = false)
    private long availablePaise;

    @Column(name = "offline_reserved_paise", nullable = false)
    private long offlineReservedPaise;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Only call after acquiring this wallet's database row lock. */
    public void debit(long paise) {
        requirePositive(paise);
        if (availablePaise < paise) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_FUNDS", "Insufficient available balance");
        }
        availablePaise -= paise;
    }

    /** Only call after acquiring this wallet's database row lock. */
    public void credit(long paise) {
        requirePositive(paise);
        try {
            long updated = Math.addExact(availablePaise, paise);
            Math.addExact(updated, offlineReservedPaise);
            availablePaise = updated;
        } catch (ArithmeticException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "BALANCE_LIMIT_EXCEEDED", "Wallet balance would exceed its storage limit");
        }
    }

    /** Same wallet, two balance buckets. Requires its database row lock. */
    public void reserveOffline(long paise) {
        debit(paise);
        offlineReservedPaise = Math.addExact(offlineReservedPaise, paise);
    }

    /** Requires wallet row lock. Settlement spends reserved funds, not available funds. */
    public void consumeOffline(long paise) {
        requirePositive(paise);
        if (offlineReservedPaise < paise)
            throw new ApiException(HttpStatus.CONFLICT, "RESERVE_MISMATCH", "Reserved balance requires reconciliation");
        offlineReservedPaise -= paise;
    }

    /** Same wallet: release unused reserve only after the server sync deadline. */
    public void releaseOffline(long paise) {
        consumeOffline(paise);
        credit(paise);
    }

    private void requirePositive(long paise) {
        if (paise <= 0) throw new IllegalArgumentException("Amount in paise must be positive");
    }
}
