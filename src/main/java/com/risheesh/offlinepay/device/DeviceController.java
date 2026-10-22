package com.risheesh.offlinepay.device;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/devices") @RequiredArgsConstructor
public class DeviceController {
    private final DeviceService service;
    public record Registration(@NotNull @Pattern(regexp = "[A-Za-z0-9._:-]{1,80}") String deviceId,
            @NotNull @Size(max = 100) String publicKey, @NotNull @Size(max = 100) String signature) {}
    @PostMapping
    DeviceService.Device register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Registration request) {
        return service.register(UUID.fromString(jwt.getSubject()), request.deviceId(), request.publicKey(), request.signature());
    }
    @PostMapping("/{deviceId}/revoke") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String deviceId) {
        service.revoke(UUID.fromString(jwt.getSubject()), deviceId);
    }
}
