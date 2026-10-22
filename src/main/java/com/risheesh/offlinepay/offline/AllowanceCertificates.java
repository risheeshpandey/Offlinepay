package com.risheesh.offlinepay.offline;

import com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.ALLOWANCE_VERSION;

/** Reconstruct immutable certificate fields; settlement counters never change the signed payload. */
public final class AllowanceCertificates {
    private AllowanceCertificates() {}
    public static AllowanceClaims claims(OfflineAllowanceEntity a, String issuer) {
        return new AllowanceClaims(ALLOWANCE_VERSION, issuer, a.getServerKeyId(), a.getId(),
                a.getOperation().getId(), a.getWallet().getId(), a.getDeviceId(), a.getDevicePublicKey(),
                a.getAllocatedPaise(), "INR", a.getCreatedAt().toEpochMilli(), a.getExpiresAt().toEpochMilli());
    }
}
