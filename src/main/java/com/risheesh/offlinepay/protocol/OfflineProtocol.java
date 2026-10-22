package com.risheesh.offlinepay.protocol;

import com.risheesh.offlinepay.common.Idempotency;
import java.util.UUID;

/** Fixed-order, newline-delimited UTF-8 payloads. Epoch milliseconds are timezone-independent. */
public final class OfflineProtocol {
    public static final String ALLOWANCE_VERSION = "OFFLINEPAY_ALLOWANCE_V1";
    public static final String PAYMENT_VERSION = "OFFLINEPAY_PAYMENT_V1";
    private OfflineProtocol() {}

    public record ServerKey(String algorithm, String issuer, String keyId, String publicKey) {}
    public record AllowanceClaims(String version, String issuer, String keyId, UUID allowanceId,
            UUID transactionId, UUID walletId, String deviceId, String devicePublicKey,
            long allocatedPaise, String currency, long issuedAtEpochMs, long expiresAtEpochMs) {}
    public record SignedAllowance(AllowanceClaims claims, String signature) {}
    public record PaymentClaims(String version, UUID paymentId, UUID allowanceId, UUID receiverWalletId,
            long amountPaise, long sequence, long paidAtEpochMs) {}
    public record PaymentProof(SignedAllowance allowance, PaymentClaims payment, String signature) {}

    public static String registration(UUID userId, String deviceId, String publicKey) {
        return "OFFLINEPAY_DEVICE_V1\n" + userId + "\n" + deviceId + "\n" + publicKey;
    }
    public static String canonical(AllowanceClaims c) {
        return String.join("\n", c.version(), c.issuer(), c.keyId(), c.allowanceId().toString(),
                c.transactionId().toString(), c.walletId().toString(), c.deviceId(), c.devicePublicKey(),
                Long.toString(c.allocatedPaise()), c.currency(), Long.toString(c.issuedAtEpochMs()),
                Long.toString(c.expiresAtEpochMs()));
    }
    public static String canonical(SignedAllowance allowance, PaymentClaims p) {
        String certificateHash = Idempotency.fingerprint(canonical(allowance.claims()) + "\n" + allowance.signature());
        return String.join("\n", p.version(), certificateHash, p.paymentId().toString(),
                p.allowanceId().toString(), p.receiverWalletId().toString(), Long.toString(p.amountPaise()),
                Long.toString(p.sequence()), Long.toString(p.paidAtEpochMs()));
    }
}
