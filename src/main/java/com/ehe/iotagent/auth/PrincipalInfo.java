package com.ehe.iotagent.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public record PrincipalInfo(
        String authorization,
        String tenantCode,
        String principalId,
        String identityRealm,
        String displayName,
        String mobileNumber) {

    public PrincipalInfo(String authorization, String tenantCode) {
        this(authorization, tenantCode, null, null, null, null);
    }

    public PrincipalInfo(String authorization, String tenantCode, String principalId, String identityRealm) {
        this(authorization, tenantCode, principalId, identityRealm, null, null);
    }

    public boolean verified() {
        return principalId != null && !principalId.isBlank()
                && identityRealm != null && !identityRealm.isBlank();
    }

    /** Runtime-compatible owner: stable account principal after verification, credential fallback only for fixtures. */
    public String ownerKey() {
        String material = verified()
                ? tenantCode + "\u0000" + identityRealm + "\u0000" + principalId
                : tenantCode + "\u0000" + authorization;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成认证主体归属键", error);
        }
    }
}
