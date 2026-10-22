package com.risheesh.offlinepay.protocol;

import com.risheesh.offlinepay.common.Idempotency;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.io.IOException;
import java.security.PrivateKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;

@Component
public class ServerSigningService {
    @Value("${offlinepay.signing-private-key-path}") private String privatePath;
    @Value("${offlinepay.signing-public-key-path}") private String publicPath;
    @Value("${offlinepay.issuer}") private String issuer;
    private PrivateKey privateKey;
    private ServerKey trusted;

    @PostConstruct
    void loadKeys() throws IOException {
        // Stable external files: never regenerate on restart or commit private keys.
        privateKey = Ed25519.privateKey(Files.readString(Path.of(privatePath)).strip());
        String publicKey = Files.readString(Path.of(publicPath)).strip();
        Ed25519.publicKey(publicKey);
        if (!issuer.matches("[A-Za-z0-9._:-]{1,80}")) throw new IllegalArgumentException("Invalid issuer");
        if (!Ed25519.verify(publicKey, "key-pair-check", Ed25519.sign(privateKey, "key-pair-check")))
            throw new IllegalArgumentException("Server signing key pair does not match");
        trusted = new ServerKey("Ed25519", issuer, Idempotency.fingerprint(publicKey), publicKey);
    }
    public ServerKey publicKey() { return trusted; }
    public String sign(AllowanceClaims claims) { return Ed25519.sign(privateKey, canonical(claims)); }
}
