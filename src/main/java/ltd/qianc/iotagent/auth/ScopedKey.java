package ltd.qianc.iotagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Builds the storage-scoped idempotency key shared by business facts.
 *
 * <p>Durable columns are bounded (VARCHAR(128)); tenant plus owner plus source
 * plus caller key can exceed that bound for ordinary Runtime identifiers. Keys
 * that fit are kept verbatim so existing rows and diagnostics stay readable;
 * longer keys are folded into a deterministic SHA-256 digest of the same
 * material, so replay detection is preserved without truncation.
 */
public final class ScopedKey {
    private static final int MAX_LENGTH = 128;

    private ScopedKey() { }

    public static String of(String... parts) {
        StringBuilder raw = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) raw.append('\u0000');
            raw.append(parts[index] == null ? "" : parts[index]);
        }
        if (raw.length() <= MAX_LENGTH) return raw.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder folded = new StringBuilder("sha256:").append(digest.length * 2);
            for (byte value : digest) folded.append(String.format("%02x", value));
            return folded.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成幂等键", error);
        }
    }
}
