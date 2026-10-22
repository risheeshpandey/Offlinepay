package com.risheesh.offlinepay.device;

import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.protocol.*;
import com.risheesh.offlinepay.wallet.*;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.http.HttpStatus.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceService {
    private final DeviceRepository devices;
    private final WalletRepository wallets;
    public record Device(String deviceId, String publicKey, Instant createdAt, Instant revokedAt) {}

    @Transactional
    public Device register(UUID userId, String deviceId, String publicKey, String signature) {
        try { Ed25519.publicKey(publicKey); }
        catch (IllegalArgumentException ex) { throw new ApiException(BAD_REQUEST, "INVALID_DEVICE_KEY", "Use Base64 X.509 Ed25519 public key"); }
        if (deviceId == null || !deviceId.matches("[A-Za-z0-9._:-]{1,80}")
                || !Ed25519.verify(publicKey, OfflineProtocol.registration(userId, deviceId, publicKey), signature))
            throw new ApiException(BAD_REQUEST, "INVALID_DEVICE_PROOF", "Device registration signature is invalid");
        WalletEntity wallet = lockWallet(userId);
        var previous = devices.findByUserIdAndDeviceId(userId, deviceId);
        if (previous.isPresent()) {
            if (!previous.get().getPublicKey().equals(publicKey) || previous.get().getRevokedAt() != null)
                throw new ApiException(CONFLICT, "DEVICE_CONFLICT", "Device ID is already bound or revoked; use a new ID/key");
            return response(previous.get());
        }
        if (devices.findByPublicKey(publicKey).isPresent())
            throw new ApiException(CONFLICT, "DEVICE_KEY_IN_USE", "Public key is already registered");
        return response(devices.save(DeviceEntity.builder().user(wallet.getUser()).deviceId(deviceId)
                .publicKey(publicKey).createdAt(Instant.now().truncatedTo(ChronoUnit.MICROS)).build()));
    }

    public DeviceEntity requireActive(UUID userId, String deviceId) {
        DeviceEntity device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new ApiException(CONFLICT, "DEVICE_NOT_REGISTERED", "Register this device before reserving funds"));
        if (device.getRevokedAt() != null)
            throw new ApiException(CONFLICT, "DEVICE_REVOKED", "Device was revoked");
        return device;
    }

    @Transactional
    public void revoke(UUID userId, String deviceId) {
        lockWallet(userId);
        DeviceEntity device = devices.findByUserIdAndDeviceId(userId, deviceId)
                .orElseThrow(() -> new ApiException(NOT_FOUND, "DEVICE_NOT_FOUND", "Device not found"));
        device.revoke(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    private WalletEntity lockWallet(UUID userId) {
        UUID id = wallets.findWalletIdByUserId(userId).orElseThrow();
        return wallets.findByIdForUpdate(id).orElseThrow();
    }
    private Device response(DeviceEntity d) { return new Device(d.getDeviceId(), d.getPublicKey(), d.getCreatedAt(), d.getRevokedAt()); }
}
