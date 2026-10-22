package com.risheesh.offlinepay.notification;

import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/notifications") @RequiredArgsConstructor @Validated
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "offlinepay.notifications.local-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationController {
    private final NotificationService service;
    @GetMapping
    public List<NotificationService.Notice> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(UUID.fromString(jwt.getSubject()), page, size);
    }
    @PostMapping("/{id}/read")
    public NotificationService.Notice read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.read(UUID.fromString(jwt.getSubject()), id);
    }
}
