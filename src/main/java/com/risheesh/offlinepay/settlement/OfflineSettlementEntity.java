package com.risheesh.offlinepay.settlement;

import com.risheesh.offlinepay.offline.OfflineAllowanceEntity;
import com.risheesh.offlinepay.payment.PaymentOperationEntity;
import com.risheesh.offlinepay.wallet.WalletEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_settlement", uniqueConstraints = @UniqueConstraint(columnNames = {"allowance_id", "sequence_number"}))
@Immutable
@Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class OfflineSettlementEntity {
    @Id @Column(name = "payment_id")
    private UUID paymentId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allowance_id", nullable = false, updatable = false)
    private OfflineAllowanceEntity allowance;
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_id", nullable = false, unique = true, updatable = false)
    private PaymentOperationEntity operation;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_wallet_id", nullable = false, updatable = false)
    private WalletEntity receiverWallet;
    @Column(name = "sequence_number", nullable = false)
    private long sequence;
    @Column(name = "proof_hash", nullable = false, length = 64)
    private String proofHash;
    @Column(name = "proof_json", nullable = false, columnDefinition = "text")
    private String proofJson;
    @Column(name = "settled_at", nullable = false, updatable = false)
    private Instant settledAt;
}
