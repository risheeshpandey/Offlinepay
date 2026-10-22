package com.risheesh.offlinepay.wallet;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WalletController {
    private final WalletService service;
    public WalletController(WalletService service) { this.service = service; }
    public record TransferRequest(@NotNull UUID receiverWalletId, @NotNull BigDecimal amount) {}
    public record TopupRequest(@NotNull BigDecimal amount) {}
    @GetMapping("/wallets/me")
    WalletService.Balance balance(@AuthenticationPrincipal Jwt jwt) { return service.balance(user(jwt)); }
    @PostMapping("/demo/topups")
    WalletService.Payment topup(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                               @Valid @RequestBody TopupRequest request) {
        return service.demoTopup(user(jwt), request.amount(), key);
    }
    @PostMapping("/payments/transfer")
    WalletService.Payment transfer(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                  @Valid @RequestBody TransferRequest request) {
        return service.transfer(user(jwt), request.receiverWalletId(), request.amount(), key);
    }
    @GetMapping("/payments/{id}")
    WalletService.Payment payment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.payment(user(jwt), id);
    }
    @GetMapping("/wallets/me/ledger")
    List<WalletService.Entry> ledger(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.ledger(user(jwt), page, size);
    }
    private static UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
