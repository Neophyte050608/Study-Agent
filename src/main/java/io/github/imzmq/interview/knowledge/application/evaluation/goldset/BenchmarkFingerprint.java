package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic SHA-256 fingerprints for benchmark inputs and identifiers. */
public final class BenchmarkFingerprint {

    private BenchmarkFingerprint() {
    }

    public static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content == null ? new byte[0] : content);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
