package com.risheesh.offlinepay.auth;

import com.risheesh.offlinepay.common.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.security.SecureRandom;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {
    private final RefreshSessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    @Value("${offlinepay.refresh-token-lifetime}")
    private Duration lifetime;

    public record Issued(UUID userId, String refreshToken, long expiresIn) {}

    @PostConstruct
    void validateLifetime() {
        if (lifetime.isZero() || lifetime.isNegative())
            throw new IllegalArgumentException("Refresh lifetime must be positive");
    }

    @Transactional
    public Issued startSession(UserEntity user) {
        Instant now = now();
        RefreshSessionEntity session = sessions.save(RefreshSessionEntity.builder()
                .user(user).createdAt(now).expiresAt(now.plus(lifetime)).build());
        return issue(session, now);
    }

    // Replay detect hone par session revoke bhi commit hona chahiye, despite HTTP 401.
    @Transactional(noRollbackFor = RefreshTokenReplayException.class)
    public Issued rotate(String rawToken) {
        String hash = hash(rawToken);
        UUID sessionId = tokens.findSessionIdByTokenHash(hash).orElseThrow(this::invalidToken);
        // Entire token family ko lock karte hain, not just one old token.
        RefreshSessionEntity session = sessions.findByIdForUpdate(sessionId).orElseThrow(this::invalidToken);
        Instant now = now();
        if (session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) throw invalidToken();
        RefreshTokenEntity current = tokens.findByTokenHash(hash).orElseThrow(this::invalidToken);
        if (current.getConsumedAt() != null) {
            session.revoke(now);
            log.warn("refresh_replay sessionId={} action=revoke_session", sessionId);
            throw new RefreshTokenReplayException();
        }
        current.consume(now);
        Issued issued = issue(session, now);
        log.info("refresh_rotation_prepared sessionId={}", sessionId);
        return issued;
    }

    @Transactional
    public void logout(String rawToken) {
        Optional<UUID> sessionId = tokens.findSessionIdByTokenHash(hash(rawToken));
        if (sessionId.isEmpty()) return;
        RefreshSessionEntity session = sessions.findByIdForUpdate(sessionId.get()).orElseThrow(this::invalidToken);
        session.revoke(now());
    }

    private Issued issue(RefreshSessionEntity session, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Database mein sirf SHA-256 hash save hota hai. Raw token sirf response mein.
        tokens.save(RefreshTokenEntity.builder().session(session)
                .tokenHash(Idempotency.fingerprint(rawToken)).createdAt(now).build());
        return new Issued(session.getUser().getId(), rawToken,
                Duration.between(now, session.getExpiresAt()).toSeconds());
    }

    private String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        return Idempotency.fingerprint(token);
    }

    private Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }
}
