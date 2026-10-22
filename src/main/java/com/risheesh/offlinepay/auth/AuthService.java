package com.risheesh.offlinepay.auth;

import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.wallet.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenService refreshTokens;

    @Value("${offlinepay.issuer}")
    private String issuer;
    @Value("${offlinepay.token-lifetime}")
    private Duration tokenLifetime;
    private String dummyPasswordHash;

    public record SignupResult(UUID userId, UUID walletId) {}
    public record TokenResult(String accessToken, String tokenType, long expiresIn,
                              @com.fasterxml.jackson.annotation.JsonIgnore String refreshToken, long refreshExpiresIn) {}

    @PostConstruct
    void initializeDummyHash() {
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public SignupResult signup(String name, String mobileNumber, String password) {
        UserEntity user = UserEntity.builder()
                .name(name.strip())
                .mobileNumber(mobileNumber)
                .passwordHash(passwordEncoder.encode(password))
                .createdAt(Instant.now())
                .build();
        try {
            // Flush here so a duplicate phone is translated before this method returns.
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "MOBILE_REGISTERED", "Mobile number is already registered");
        }
        WalletEntity wallet = walletRepository.save(WalletEntity.builder()
                .user(user)
                .availablePaise(0)
                .createdAt(Instant.now())
                .build());
        return new SignupResult(user.getId(), wallet.getId());
    }

    @Transactional
    public TokenResult login(String mobileNumber, String password) {
        Optional<UserEntity> user = userRepository.findByMobileNumber(mobileNumber);
        String storedHash = user.map(UserEntity::getPasswordHash).orElse(dummyPasswordHash);
        boolean matches = passwordEncoder.matches(password, storedHash);
        if (user.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
        }
        RefreshTokenService.Issued issued = refreshTokens.startSession(user.get());
        return createToken(issued);
    }

    // No outer transaction: replay revocation must commit before a 401 propagates.
    public TokenResult refresh(String token) {
        return createToken(refreshTokens.rotate(token));
    }

    public void logout(String token) { refreshTokens.logout(token); }

    private TokenResult createToken(RefreshTokenService.Issued issued) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(issued.userId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(tokenLifetime))
                .id(UUID.randomUUID().toString())
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResult(token, "Bearer", tokenLifetime.toSeconds(), issued.refreshToken(), issued.expiresIn());
    }
}
