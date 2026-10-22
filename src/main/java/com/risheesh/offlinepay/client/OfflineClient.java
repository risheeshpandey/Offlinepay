package com.risheesh.offlinepay.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.risheesh.offlinepay.common.Idempotency;
import com.risheesh.offlinepay.protocol.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;
import static com.risheesh.offlinepay.protocol.OfflineProtocol.*;
import static java.nio.file.StandardOpenOption.*;

/** Local learning client: no Spring startup or network calls. Never upload private-key.txt. */
public class OfflineClient {
    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    public record Registration(String deviceId, String publicKey, String signature) {}
    public record LocalState(long spentPaise, long sequence) {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "keygen" -> {
                if (args.length < 2 || args.length > 3) { usage(); return; }
                keygen(Path.of(args[1]), args.length == 3 ? args[2] : "offlinepay");
            }
            case "register" -> {
                if (args.length != 4) { usage(); return; }
                registration(Path.of(args[1]), UUID.fromString(args[2]), args[3]);
            }
            case "pay" -> {
                if (args.length != 7) { usage(); return; }
                pay(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), UUID.fromString(args[4]),
                        Long.parseLong(args[5]), Path.of(args[6]));
            }
            case "verify" -> {
                if (args.length != 4) { usage(); return; }
                verify(Path.of(args[1]), Path.of(args[2]), UUID.fromString(args[3]));
            }
            default -> usage();
        }
    }
    private static void usage() {
        System.out.println("keygen <directory> [issuer]\nregister <device-directory> <user-id> <device-id>\n"
                + "pay <device-directory> <certificate.json> <trusted-server-key.json> <receiver-wallet-id> <amount-paise> <output.json>\n"
                + "verify <trusted-server-key.json> <proof.json> <expected-receiver-wallet-id>");
    }
    public static void keygen(Path directory, String issuer) throws Exception {
        if (!issuer.matches("[A-Za-z0-9._:-]{1,80}")) throw new IllegalArgumentException("Invalid issuer");
        Files.createDirectories(directory);
        // An existing key pair must never be overwritten (server certificates depend on it).
        if (Files.exists(directory.resolve("private-key.txt")) || Files.exists(directory.resolve("public-key.txt")))
            throw new IllegalStateException("Keys already exist; keep them");
        var pair = Ed25519.generate();
        Path privateFile = directory.resolve("private-key.txt");
        Files.writeString(privateFile, Ed25519.encode(pair.getPrivate()), CREATE_NEW);
        try { Files.setPosixFilePermissions(privateFile, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Windows: restrict folder access yourself. */ }
        String publicKey = Ed25519.encode(pair.getPublic());
        Files.writeString(directory.resolve("public-key.txt"), publicKey, CREATE_NEW);
        writeNew(directory.resolve("trusted-server-key.json"),
                new ServerKey("Ed25519", issuer, Idempotency.fingerprint(publicKey), publicKey));
        System.out.println("Keys written to " + directory + "; private key stays local.");
    }
    public static Registration registration(Path directory, UUID user, String deviceId) throws Exception {
        if (!deviceId.matches("[A-Za-z0-9._:-]{1,80}")) throw new IllegalArgumentException("Invalid device ID");
        String publicKey = Files.readString(directory.resolve("public-key.txt")).strip();
        String signature = Ed25519.sign(Ed25519.privateKey(Files.readString(directory.resolve("private-key.txt")).strip()),
                OfflineProtocol.registration(user, deviceId, publicKey));
        Registration registration = new Registration(deviceId, publicKey, signature);
        writeNew(directory.resolve("registration.json"), registration);
        System.out.println("Registration JSON written; send public key + signature, never the private key.");
        return registration;
    }
    public static PaymentProof pay(Path directory, Path certificateFile, Path trustedFile, UUID receiver,
                                   long amountPaise, Path output) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("Output exists; choose a new filename");
        SignedAllowance signed = JSON.readValue(certificateFile.toFile(), SignedAllowance.class);
        ServerKey trusted = JSON.readValue(trustedFile.toFile(), ServerKey.class);
        Instant now = Instant.now();
        AllowanceClaims c = OfflineProofVerifier.verifyAllowance(signed, trusted, now);
        String publicKey = Files.readString(directory.resolve("public-key.txt")).strip();
        if (!c.devicePublicKey().equals(publicKey)) throw new IllegalArgumentException("Allowance belongs to another device key");
        Path stateFile = directory.resolve("state-" + c.allowanceId() + ".json");
        // Cross-process lock prevents accidental simultaneous spending by this demo client.
        try (FileChannel channel = FileChannel.open(directory.resolve("client.lock"), CREATE, WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another payment is running");
            LocalState state = Files.exists(stateFile) ? JSON.readValue(stateFile.toFile(), LocalState.class) : new LocalState(0, 0);
            if (state.spentPaise() < 0 || state.sequence() < 0 || amountPaise <= 0)
                throw new IllegalArgumentException("Invalid local state or amount");
            long total = Math.addExact(state.spentPaise(), amountPaise);
            if (total > c.allocatedPaise()) throw new IllegalArgumentException("Insufficient local allowance balance");
            PaymentClaims payment = new PaymentClaims(PAYMENT_VERSION, UUID.randomUUID(), c.allowanceId(), receiver,
                    amountPaise, Math.addExact(state.sequence(), 1), now.toEpochMilli());
            String signature = Ed25519.sign(Ed25519.privateKey(Files.readString(directory.resolve("private-key.txt")).strip()),
                    canonical(signed, payment));
            PaymentProof proof = new PaymentProof(signed, payment, signature);
            OfflineProofVerifier.verify(proof, trusted, receiver, Instant.now());
            Path temp = Files.createTempFile(directory, "state-", ".tmp");
            JSON.writeValue(temp.toFile(), new LocalState(total, payment.sequence()));
            Files.move(temp, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            writeNew(output, proof);
            System.out.println("Proof written to " + output + "; local remaining paise=" + (c.allocatedPaise() - total));
            return proof;
        }
    }
    public static void verify(Path trusted, Path proof, UUID receiver) throws Exception {
        OfflineProofVerifier.verify(JSON.readValue(proof.toFile(), PaymentProof.class),
                JSON.readValue(trusted.toFile(), ServerKey.class), receiver, Instant.now());
        System.out.println("VERIFIED_NOT_SETTLED: signatures valid; no receiver credit or global double-spend guarantee.");
    }
    private static void writeNew(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value), CREATE_NEW);
    }
}
