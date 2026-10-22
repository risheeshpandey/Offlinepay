package com.risheesh.offlinepay.offline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/offline-allowances")
@RequiredArgsConstructor
public class OfflineAllowanceController {
    private final OfflineAllowanceService service;
    public record ReserveRequest(@NotNull BigDecimal amount,
                                 @NotNull @Pattern(regexp = "[A-Za-z0-9._:-]{1,80}") String deviceId) {}

    @PostMapping
    OfflineAllowanceService.Allowance reserve(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReserveRequest request) {
        return service.reserve(UUID.fromString(jwt.getSubject()), request.amount(), request.deviceId(), key);
    }

    @GetMapping("/{id}/certificate")
    com.risheesh.offlinepay.protocol.OfflineProtocol.SignedAllowance certificate(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.certificate(UUID.fromString(jwt.getSubject()), id);
    }

    @GetMapping("/{id}")
    OfflineAllowanceService.Allowance get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(UUID.fromString(jwt.getSubject()), id);
    }

    @GetMapping
    List<OfflineAllowanceService.Allowance> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(UUID.fromString(jwt.getSubject()), page, size);
    }
}
