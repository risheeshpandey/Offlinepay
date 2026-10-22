package com.risheesh.offlinepay.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;

public final class Idempotency {
    private Idempotency() {}

    public static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Use an 8-128 character key containing letters, numbers, hyphens or underscores");
        }
    }

    public static String fingerprint(String normalizedRequest) {
        try {
            byte[] bytes = normalizedRequest.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
