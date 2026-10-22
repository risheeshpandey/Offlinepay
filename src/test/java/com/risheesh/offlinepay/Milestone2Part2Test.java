package com.risheesh.offlinepay;

import com.fasterxml.jackson.databind.*;
import com.risheesh.offlinepay.auth.*;
import com.risheesh.offlinepay.client.OfflineClient;
import com.risheesh.offlinepay.common.ApiException;
import com.risheesh.offlinepay.device.DeviceService;
import com.risheesh.offlinepay.offline.OfflineAllowanceService;
import com.risheesh.offlinepay.protocol.*;
import com.risheesh.offlinepay.wallet.WalletService;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class Milestone2Part2Test {
    @Autowired AuthService auth;
    @Autowired DeviceService devices;
    @Autowired WalletService wallets;
    @Autowired OfflineAllowanceService offline;
    @Autowired ServerSigningService signer;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    private static final AtomicInteger PHONE = new AtomicInteger();
    private record User(UUID id, UUID wallet, String mobile, KeyPair pair) {}
    private record Csrf(Cookie cookie, String token) {}
    private User user() {
        String mobile = "7" + String.format("%09d", PHONE.incrementAndGet());
        var u = auth.signup("Part Two", mobile, "DemoPassword123!");
        return new User(u.userId(), u.walletId(), mobile, Ed25519.generate());
    }
    private void register(User u) {
        String publicKey = Ed25519.encode(u.pair().getPublic());
        devices.register(u.id(), "DEVICE-P2", publicKey,
                Ed25519.sign(u.pair().getPrivate(), registration(u.id(), "DEVICE-P2", publicKey)));
    }
    private SignedAllowance certificate(User u) {
        register(u); wallets.demoTopup(u.id(), new BigDecimal("750"), UUID.randomUUID().toString());
        var a = offline.reserve(u.id(), new BigDecimal("300"), "DEVICE-P2", UUID.randomUUID().toString());
        return offline.certificate(u.id(), a.allowanceId());
    }
    private PaymentProof proof(User sender, SignedAllowance certificate, UUID receiver, long amount) {
        PaymentClaims p = new PaymentClaims(PAYMENT_VERSION, UUID.randomUUID(), certificate.claims().allowanceId(),
                receiver, amount, 1, Instant.now().toEpochMilli());
        return new PaymentProof(certificate, p, Ed25519.sign(sender.pair().getPrivate(), canonical(certificate, p)));
    }
    private Csrf csrf() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        return new Csrf(cookie(response, "XSRF-TOKEN"), json.readTree(response.getContentAsString()).get("token").asText());
    }
    private MockCookie cookie(org.springframework.mock.web.MockHttpServletResponse response, String name) {
        return response.getHeaders("Set-Cookie").stream().filter(s -> s.startsWith(name + "="))
                .map(MockCookie::parse).findFirst().orElseThrow();
    }
    private org.springframework.mock.web.MockHttpServletResponse login(User u, Csrf csrf) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mobileNumber", u.mobile(), "password", "DemoPassword123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("refreshToken").doesNotExist())
                .andExpect(jsonPath("refreshExpiresIn").doesNotExist()).andReturn().getResponse();
    }

    @Test void cookieOnlyRotationReplayAndLogoutPersistWithoutAnyRefreshBody() throws Exception {
        User u = user(); Csrf csrf = csrf(); var response = login(u, csrf);
        MockCookie rt1 = cookie(response, AuthCookies.REFRESH);
        assertTrue(rt1.isHttpOnly()); assertFalse(rt1.getSecure());
        assertEquals("Strict", rt1.getSameSite()); assertEquals("/api/v1/auth", rt1.getPath());
        assertTrue(rt1.getMaxAge() > 0); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertFalse(response.getContentAsString().contains(rt1.getValue()));
        var refreshed = mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rt1)
                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("refreshToken").doesNotExist()).andReturn().getResponse();
        Cookie rt2 = cookie(refreshed, AuthCookies.REFRESH); assertNotEquals(rt1.getValue(), rt2.getValue());
        String at = json.readTree(refreshed.getContentAsString()).get("accessToken").asText();
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", "Bearer " + at)).andExpect(status().isOk());
        var replay = mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rt1)
                .header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("REFRESH_TOKEN_REUSED"))
                .andReturn().getResponse();
        assertEquals(0, cookie(replay, AuthCookies.REFRESH).getMaxAge());
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rt2).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("INVALID_REFRESH_TOKEN"));
        Cookie rt3 = cookie(login(u, csrf), AuthCookies.REFRESH);
        var logout = mvc.perform(post("/api/v1/auth/logout").cookie(csrf.cookie(), rt3)
                .header("X-XSRF-TOKEN", csrf.token())).andExpect(status().isNoContent()).andReturn().getResponse();
        assertEquals(0, cookie(logout, AuthCookies.REFRESH).getMaxAge());
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rt3).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test void authPostsRequireCsrfAndBodyTokenCannotReplaceCookie() throws Exception {
        User u = user(); Csrf csrf = csrf(); Cookie rt = cookie(login(u, csrf), AuthCookies.REFRESH);
        for (String endpoint : List.of("login", "signup", "refresh", "logout")) {
            mvc.perform(post("/api/v1/auth/" + endpoint).cookie(rt)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie(), rt).header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", rt.getValue()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallets/me").cookie(rt)).andExpect(status().isUnauthorized());
        // CSRF rejections must not consume or revoke a valid refresh token.
        assertNotNull(auth.refresh(rt.getValue()).accessToken());
    }

    @Test void apiInstantSerializationUsesIstAndRoundTripsToSameInstant() throws Exception {
        User u = user(); var certificate = certificate(u); String at = auth.login(u.mobile(), "DemoPassword123!").accessToken();
        var response = mvc.perform(get("/api/v1/offline-allowances/" + certificate.claims().allowanceId())
                .header("Authorization", "Bearer " + at)).andExpect(status().isOk()).andReturn().getResponse();
        String expiry = json.readTree(response.getContentAsString()).get("expiresAt").asText();
        assertTrue(expiry.endsWith("+05:30"));
        assertEquals(Instant.ofEpochMilli(certificate.claims().expiresAtEpochMs()), Instant.parse(expiry));
        var error = mvc.perform(post("/api/v1/offline-allowances").header("Authorization", "Bearer " + at)
                .header("Idempotency-Key", "bad-device-ist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1,\"deviceId\":\"bad|device\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertTrue(json.readTree(error.getContentAsString()).get("timestamp").asText().endsWith("+05:30"));
    }

    @Test void deviceRequiresPossessionAndCannotRebindOrCrossUserKeys() {
        User a = user(), b = user(); String pub = Ed25519.encode(a.pair().getPublic());
        String registration = registration(a.id(), "DEVICE-P2", pub);
        assertEquals("INVALID_DEVICE_PROOF", assertThrows(ApiException.class, () ->
                devices.register(a.id(), "DEVICE-P2", pub, Ed25519.sign(b.pair().getPrivate(), registration))).code());
        register(a); register(a); // Safe identical registration retry.
        assertEquals("DEVICE_KEY_IN_USE", assertThrows(ApiException.class, () -> devices.register(b.id(), "OTHER", pub,
                Ed25519.sign(a.pair().getPrivate(), registration(b.id(), "OTHER", pub)))).code());
        String other = Ed25519.encode(b.pair().getPublic());
        assertEquals("DEVICE_CONFLICT", assertThrows(ApiException.class, () -> devices.register(a.id(), "DEVICE-P2", other,
                Ed25519.sign(b.pair().getPrivate(), registration(a.id(), "DEVICE-P2", other)))).code());
    }

    @Test void reservationRequiresRegisteredDeviceAndRevocationBlocksNewCertificates() {
        User a = user(); wallets.demoTopup(a.id(), new BigDecimal("750"), UUID.randomUUID().toString());
        assertEquals("DEVICE_NOT_REGISTERED", assertThrows(ApiException.class, () -> offline.reserve(a.id(),
                new BigDecimal("300"), "DEVICE-P2", UUID.randomUUID().toString())).code());
        assertEquals(new BigDecimal("750.00"), wallets.balance(a.id()).availableBalance());
        register(a); var allowance = offline.reserve(a.id(), new BigDecimal("300"), "DEVICE-P2", UUID.randomUUID().toString());
        devices.revoke(a.id(), "DEVICE-P2");
        assertEquals("DEVICE_REVOKED", assertThrows(ApiException.class, () -> offline.certificate(a.id(), allowance.allowanceId())).code());
        assertEquals("DEVICE_REVOKED", assertThrows(ApiException.class, () -> offline.reserve(a.id(), BigDecimal.ONE,
                "DEVICE-P2", UUID.randomUUID().toString())).code());
        assertEquals(new BigDecimal("300.00"), wallets.balance(a.id()).offlineReservedBalance());
    }

    @Test void signedCertificateStableOnRetryAndAccessibleOnlyToOwner() {
        User a = user(), b = user(); register(a); wallets.demoTopup(a.id(), new BigDecimal("750"), UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        var allowance = offline.reserve(a.id(), new BigDecimal("300"), "DEVICE-P2", key);
        SignedAllowance original = offline.certificate(a.id(), allowance.allowanceId());
        offline.reserve(a.id(), new BigDecimal("300"), "DEVICE-P2", key);
        assertEquals(original, offline.certificate(a.id(), allowance.allowanceId()));
        assertEquals("ALLOWANCE_NOT_FOUND", assertThrows(ApiException.class, () -> offline.certificate(b.id(), allowance.allowanceId())).code());
        assertEquals(Ed25519.encode(a.pair().getPublic()), original.claims().devicePublicKey());
        assertEquals(30000, original.claims().allocatedPaise());
        assertDoesNotThrow(() -> OfflineProofVerifier.verifyAllowance(original, signer.publicKey(), Instant.now()));
    }

    @Test void validProofVerifiesForReceiverWithoutChangingAnyBalanceOrJournal() throws Exception {
        User a = user(), b = user(); SignedAllowance c = certificate(a); PaymentProof p = proof(a, c, b.wallet(), 10000);
        String at = auth.login(b.mobile(), "DemoPassword123!").accessToken();
        long entries = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Long.class);
        mvc.perform(post("/api/v1/offline-protocol/verify").header("Authorization", "Bearer " + at)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(p)))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("VERIFIED_NOT_SETTLED"));
        assertEquals(new BigDecimal("0.00"), wallets.balance(b.id()).availableBalance());
        assertEquals(new BigDecimal("450.00"), wallets.balance(a.id()).availableBalance());
        assertEquals(entries, jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry", Long.class));
        String senderAt = auth.login(a.mobile(), "DemoPassword123!").accessToken();
        mvc.perform(post("/api/v1/offline-protocol/verify").header("Authorization", "Bearer " + senderAt)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(p)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_OFFLINE_PROOF"));
    }

    @Test void rejectsChangedAmountReceiverPaymentSignatureAndUntrustedServer() {
        User a = user(), b = user(); SignedAllowance c = certificate(a); PaymentProof valid = proof(a, c, b.wallet(), 10000);
        var p = valid.payment();
        var changed = new PaymentClaims(p.version(), p.paymentId(), p.allowanceId(), p.receiverWalletId(), 20000, p.sequence(), p.paidAtEpochMs());
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(new PaymentProof(c, changed, valid.signature()), signer.publicKey(), b.wallet(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(valid, signer.publicKey(), UUID.randomUUID(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(new PaymentProof(c, p, Ed25519.sign(b.pair().getPrivate(), canonical(c,p))), signer.publicKey(), b.wallet(), Instant.now()));
        String fakeSignature = Ed25519.sign(b.pair().getPrivate(), canonical(c.claims()));
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(new PaymentProof(new SignedAllowance(c.claims(), fakeSignature), p, valid.signature()), signer.publicKey(), b.wallet(), Instant.now()));
        var claims = c.claims();
        var forged = new AllowanceClaims(claims.version(), claims.issuer(), claims.keyId(), claims.allowanceId(), claims.transactionId(), claims.walletId(), claims.deviceId(), claims.devicePublicKey(), 90000, claims.currency(), claims.issuedAtEpochMs(), claims.expiresAtEpochMs());
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verifyAllowance(new SignedAllowance(forged, c.signature()), signer.publicKey(), Instant.now()));
    }

    @Test void rejectsExpiredAllowanceOversizedPaymentAndInvalidTimesEvenWithValidDeviceSignature() {
        User a = user(), b = user(); var c = certificate(a);
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verifyAllowance(c, signer.publicKey(), Instant.ofEpochMilli(c.claims().expiresAtEpochMs())));
        var over = proof(a, c, b.wallet(), 30001);
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(over, signer.publicKey(), b.wallet(), Instant.now()));
        var p = new PaymentClaims(PAYMENT_VERSION, UUID.randomUUID(), c.claims().allowanceId(), b.wallet(), 100, 1, c.claims().issuedAtEpochMs() - 1);
        assertThrows(IllegalArgumentException.class, () -> OfflineProofVerifier.verify(new PaymentProof(c, p, Ed25519.sign(a.pair().getPrivate(), canonical(c,p))), signer.publicKey(), b.wallet(), Instant.now()));
    }

    @Test void legacyReservationRemainsReservedButCannotBeSilentlySigned() {
        User a = user(); var c = certificate(a);
        jdbc.update("UPDATE offline_allowance SET device_public_key=NULL, server_key_id=NULL, server_signature=NULL WHERE id=?", c.claims().allowanceId());
        assertEquals("LEGACY_ALLOWANCE_UNSIGNED", assertThrows(ApiException.class, () -> offline.certificate(a.id(), c.claims().allowanceId())).code());
        assertEquals(new BigDecimal("300.00"), wallets.balance(a.id()).offlineReservedBalance());
    }

    @Test void localClientCreatesProofsOfflineAndTracksCumulativeBudget() throws Exception {
        User a = user(), b = user(); Path device = directory.resolve("device"); OfflineClient.keygen(device, "offlinepay");
        var registration = OfflineClient.registration(device, a.id(), "LOCAL-DEVICE");
        devices.register(a.id(), registration.deviceId(), registration.publicKey(), registration.signature());
        wallets.demoTopup(a.id(), new BigDecimal("750"), UUID.randomUUID().toString());
        var allowance = offline.reserve(a.id(), new BigDecimal("300"), "LOCAL-DEVICE", UUID.randomUUID().toString());
        Path certificate = directory.resolve("certificate.json"), trusted = directory.resolve("trusted.json");
        json.writeValue(certificate.toFile(), offline.certificate(a.id(), allowance.allowanceId()));
        json.writeValue(trusted.toFile(), signer.publicKey());
        Path p1 = directory.resolve("payment1.json"), p2 = directory.resolve("payment2.json");
        assertEquals(1, OfflineClient.pay(device, certificate, trusted, b.wallet(), 10000, p1).payment().sequence());
        assertEquals(2, OfflineClient.pay(device, certificate, trusted, b.wallet(), 20000, p2).payment().sequence());
        OfflineClient.verify(trusted, p1, b.wallet());
        assertThrows(IllegalArgumentException.class, () -> OfflineClient.pay(device, certificate, trusted, b.wallet(), 1, directory.resolve("over.json")));
        assertThrows(IllegalStateException.class, () -> OfflineClient.keygen(device, "offlinepay"));
    }
}
