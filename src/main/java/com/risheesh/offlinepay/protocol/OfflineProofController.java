package com.risheesh.offlinepay.protocol;

import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.wallet.WalletRepository;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;

@RestController @RequestMapping("/api/v1/offline-protocol") @RequiredArgsConstructor
public class OfflineProofController {
    private final ServerSigningService signer;
    private final WalletRepository wallets;
    public record Verification(String status, UUID paymentId, String message) {}

    @GetMapping("/server-key")
    ServerKey key() { return signer.publicKey(); }

    @PostMapping("/verify")
    Verification verify(@AuthenticationPrincipal Jwt jwt, @RequestBody PaymentProof proof) {
        UUID receiver = wallets.findWalletIdByUserId(UUID.fromString(jwt.getSubject())).orElseThrow();
        try { OfflineProofVerifier.verify(proof, signer.publicKey(), receiver, Instant.now()); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OFFLINE_PROOF", "Proof signature, recipient, amount or validity check failed");
        }
        return new Verification("VERIFIED_NOT_SETTLED", proof.payment().paymentId(),
                "Signatures and fields verified; no credit, settlement or global double-spend check performed");
    }
}
