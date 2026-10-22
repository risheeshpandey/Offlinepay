package com.risheesh.offlinepay.settlement;

import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.protocol.OfflineProtocol.PaymentProof;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/offline-sync")
@RequiredArgsConstructor
public class OfflineSyncController {
    private final OfflineSettlementService service;

    @PostMapping("/payments")
    OfflineSettlementService.Settlement sync(@AuthenticationPrincipal Jwt jwt, @RequestBody PaymentProof proof) {
        try { return service.sync(UUID.fromString(jwt.getSubject()), proof); }
        catch (DataIntegrityViolationException ex) {
            // A global UUID collision can race across different senders; rollback is already complete here.
            throw new ApiException(HttpStatus.CONFLICT, "SETTLEMENT_CONFLICT", "Payment identity conflicts with committed history");
        }
    }

    @GetMapping("/payments/{paymentId}")
    OfflineSettlementService.Settlement get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) {
        return service.get(UUID.fromString(jwt.getSubject()), paymentId);
    }

    @PostMapping("/allowances/{allowanceId}/release")
    OfflineSettlementService.Release release(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID allowanceId) {
        return service.release(UUID.fromString(jwt.getSubject()), allowanceId);
    }
}
