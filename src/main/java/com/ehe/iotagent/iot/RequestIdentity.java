package com.ehe.iotagent.iot;

import java.util.Map;
import java.util.Objects;
import com.ehe.iotagent.auth.PrincipalContext;
import com.ehe.iotagent.auth.PrincipalInfo;

/**
 * Identity captured for one gateway call. It is deliberately explicit so a device
 * scope cannot be loaded without selecting its Office or Company authorization domain.
 */
public record RequestIdentity(String authorization, String tenantCode, IotSourceEnum source, String ownerKey) {

    public RequestIdentity(String authorization, String tenantCode, IotSourceEnum source) {
        this(authorization, tenantCode, source, ownerHash(authorization, tenantCode));
    }

    public RequestIdentity {
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalArgumentException("Authorization is required");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("Tenantcode is required");
        }
        Objects.requireNonNull(source, "source");
        if (ownerKey == null || ownerKey.isBlank()) throw new IllegalArgumentException("Owner key is required");
    }

    private static String ownerHash(String authorization, String tenantCode) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((tenantCode + "\u0000" + authorization).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public static RequestIdentity fromPrincipal(IotSourceEnum source) {
        PrincipalInfo principal = PrincipalContext.getPrincipalInfo();
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source, principal.ownerKey());
    }

    /** Exact customer gateway headers; source is a local authorization boundary, not a forwarded credential. */
    public Map<String, String> gatewayHeaders() {
        return Map.of("Authorization", authorization, "Tenantcode", tenantCode);
    }
}
