package com.risheesh.notifications;

import com.risheesh.offlinepay.notification.NotificationService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/notifications") @RequiredArgsConstructor @Validated
public class NotificationApi {
    private final NotificationService service;
    @GetMapping
    public List<NotificationService.Notice> list(Authentication auth,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(UUID.fromString(auth.getName()), page, size);
    }
    @PostMapping("/{id}/read")
    public NotificationService.Notice read(Authentication auth, @PathVariable UUID id) {
        return service.read(UUID.fromString(auth.getName()), id);
    }
}
