package com.risheesh.offlinepay.device;

import com.risheesh.offlinepay.auth.UserEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registered_device", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id"}))
@Getter @Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class DeviceEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private UserEntity user;
    @Column(name = "device_id", nullable = false, length = 80, updatable = false)
    private String deviceId;
    @Column(name = "public_key", nullable = false, length = 100, unique = true, updatable = false)
    private String publicKey;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
}
