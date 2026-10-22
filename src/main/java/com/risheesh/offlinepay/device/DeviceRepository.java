package com.risheesh.offlinepay.device;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<DeviceEntity, UUID> {
    Optional<DeviceEntity> findByUserIdAndDeviceId(UUID userId, String deviceId);
    Optional<DeviceEntity> findByPublicKey(String publicKey);
}
