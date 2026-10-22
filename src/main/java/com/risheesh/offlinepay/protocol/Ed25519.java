package com.risheesh.offlinepay.protocol;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/** JDK crypto only. Public keys: Base64 X.509 DER; private keys: Base64 PKCS#8 DER. */
public final class Ed25519 {
    private Ed25519() {}
    public static KeyPair generate() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    public static String encode(Key key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }
    public static PublicKey publicKey(String encoded) {
        try {
            if (encoded == null || encoded.length() > 100) throw new IllegalArgumentException("Invalid Ed25519 public key");
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (!encode(key).equals(encoded) || key.getEncoded().length != 44)
                throw new IllegalArgumentException("Use canonical Base64 X.509 Ed25519 key");
            return key;
        } catch (GeneralSecurityException ex) { throw new IllegalArgumentException("Invalid Ed25519 public key", ex); }
    }
    public static PrivateKey privateKey(String encoded) {
        try { return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded))); }
        catch (GeneralSecurityException ex) { throw new IllegalArgumentException("Invalid Ed25519 private key", ex); }
    }
    public static String sign(PrivateKey key, String payload) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key); signer.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("Signing failed", ex); }
    }
    public static boolean verify(String publicKey, String payload, String signature) {
        try {
            if (signature == null || signature.length() != 88) return false;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(publicKey)); verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException ex) { return false; }
    }
}
