package com.risheesh.offlinepay.protocol;

import com.risheesh.offlinepay.common.Idempotency;
import java.time.Instant;
import java.util.UUID;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;

/** Pure verifier: no database, HTTP, Spring context or receiver credit. Also used by the offline client. */
public final class OfflineProofVerifier {
    private OfflineProofVerifier() {}
    public static AllowanceClaims verifyAllowance(SignedAllowance signed, ServerKey trusted, Instant now) {
        AllowanceClaims c = verifyAllowanceSignature(signed, trusted);
        if (now.toEpochMilli() < c.issuedAtEpochMs() || now.toEpochMilli() >= c.expiresAtEpochMs())
            throw invalid("Allowance is not currently valid");
        return c;
    }

    private static AllowanceClaims verifyAllowanceSignature(SignedAllowance signed, ServerKey trusted) {
        if (signed == null || signed.claims() == null) throw invalid("Missing allowance");
        AllowanceClaims c = signed.claims();
        if (!"Ed25519".equals(trusted.algorithm()) || !ALLOWANCE_VERSION.equals(c.version())
                || !trusted.issuer().equals(c.issuer()) || !trusted.keyId().equals(c.keyId())
                || !Idempotency.fingerprint(trusted.publicKey()).equals(trusted.keyId()))
            throw invalid("Untrusted allowance issuer/key/version");
        if (c.allowanceId() == null || c.transactionId() == null || c.walletId() == null
                || c.deviceId() == null || !c.deviceId().matches("[A-Za-z0-9._:-]{1,80}")
                || c.allocatedPaise() <= 0 || c.allocatedPaise() > 1_000_000_000L
                || !"INR".equals(c.currency()) || c.issuedAtEpochMs() >= c.expiresAtEpochMs())
            throw invalid("Invalid allowance fields");
        Ed25519.publicKey(c.devicePublicKey());
        if (!Ed25519.verify(trusted.publicKey(), canonical(c), signed.signature()))
            throw invalid("Invalid server signature");
        return c;
    }
    public static void verify(PaymentProof proof, ServerKey trusted, UUID expectedReceiver, Instant now) {
        verifyAllowance(proof == null ? null : proof.allowance(), trusted, now);
        verifyForSettlement(proof, trusted, expectedReceiver, now);
    }

    /** Checks signatures and signed payment time; DB sync deadline is enforced by settlement service. */
    public static void verifyForSettlement(PaymentProof proof, ServerKey trusted, UUID expectedReceiver, Instant now) {
        if (proof == null || proof.payment() == null || expectedReceiver == null) throw invalid("Missing payment");
        AllowanceClaims c = verifyAllowanceSignature(proof.allowance(), trusted);
        PaymentClaims p = proof.payment();
        if (!PAYMENT_VERSION.equals(p.version()) || p.paymentId() == null
                || !c.allowanceId().equals(p.allowanceId()) || !expectedReceiver.equals(p.receiverWalletId())
                || c.walletId().equals(p.receiverWalletId()) || p.amountPaise() <= 0
                || p.amountPaise() > c.allocatedPaise() || p.sequence() < 1
                || p.paidAtEpochMs() < c.issuedAtEpochMs() || p.paidAtEpochMs() >= c.expiresAtEpochMs()
                || p.paidAtEpochMs() > now.toEpochMilli())
            throw invalid("Invalid payment fields, recipient, amount or time");
        if (!Ed25519.verify(c.devicePublicKey(), canonical(proof.allowance(), p), proof.signature()))
            throw invalid("Invalid device signature");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
