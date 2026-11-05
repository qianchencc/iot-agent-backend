package ltd.qianc.iotagent.iot;

import java.util.List;

/**
 * The persisted candidate-set fact. Members retain control/physical bindings and
 * are only used by server-side materialization; they are never an HTTP view.
 */
public record DeviceCandidateRecord(
        String candidateSetId,
        String conversationId,
        String tenantCode,
        String ownerKey,
        IotSourceEnum source,
        String catalogFingerprint,
        DeviceCandidateQuery query,
        List<AuthorizedDevice> members,
        boolean complete,
        int totalMatches,
        String createdAt,
        String resolutionMode,
        String handle) {


    public DeviceCandidateRecord {
        if (candidateSetId == null || candidateSetId.isBlank()) {
            throw new IllegalArgumentException("candidateSetId is required");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode is required");
        }
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("ownerKey is required");
        }
        if (source == null) throw new IllegalArgumentException("source is required");
        if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
            throw new IllegalArgumentException("catalogFingerprint is required");
        }
        query = java.util.Objects.requireNonNull(query, "query");
        members = List.copyOf(members == null ? List.of() : members);
        resolutionMode = resolutionMode == null ? "unresolved" : resolutionMode;
        if (totalMatches < members.size()) throw new IllegalArgumentException("totalMatches is smaller than members");
        if (createdAt == null || createdAt.isBlank()) createdAt = java.time.Instant.now().toString();
    }

    /** Whole-scope inclusion permits authorized browsing; exclusions must always be exact. */
    public boolean permitsScopeExpansion(boolean exclusion) {
        return "exact".equals(resolutionMode) || !exclusion && "browse".equals(resolutionMode);
    }

    /** Stable member/catalog/control evidence; presentation order, aliases and mutable observations are excluded. */
    public static String scopeFingerprint(List<AuthorizedDevice> members) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            members.stream().sorted(java.util.Comparator.comparing(AuthorizedDevice::id)).forEach(device -> {
                fingerprintField(digest, device.catalogFingerprint());
                fingerprintField(digest, device.controlRef());
                fingerprintField(digest, device.hubDeviceId());
                fingerprintField(digest, device.smartAreaCode());
                digest.update(device.observationOnly() ? (byte) 1 : (byte) 0);
            });
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void fingerprintField(java.security.MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    /**
     * Runtime app.ts persistCandidateSet: the model-side set handle is sN and the
     * device handles d{N}_{k} are positions in this record's ordered member list.
     */
    public String deviceHandle(int position) {
        return "d" + handleNumber() + "_" + (position + 1);
    }

    /** Resolves a d{N}_{k} handle of this set into the member id; every other ref passes through unchanged. */
    public String resolveDeviceRef(String ref) {
        if (ref == null) return ref;
        java.util.regex.Matcher matcher = DEVICE_HANDLE.matcher(ref);
        if (!matcher.matches()) return ref;
        String number = handleNumber();
        if (number.isBlank()) return ref;
        try {
            if (Integer.parseInt(number) != Integer.parseInt(matcher.group(1))) return ref;
            int position = Integer.parseInt(matcher.group(2)) - 1;
            return position >= 0 && position < members.size() ? members.get(position).id() : ref;
        } catch (NumberFormatException invalidHandle) {
            return ref;
        }
    }

    private String handleNumber() {
        return handle != null && handle.startsWith("s") && handle.length() > 1 ? handle.substring(1) : "";
    }

    private static final java.util.regex.Pattern DEVICE_HANDLE = java.util.regex.Pattern.compile("^d(\\d+)_(\\d+)$");
}
