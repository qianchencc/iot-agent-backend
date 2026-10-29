package ltd.qianc.iotagent.iot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A device identity returned by a server-authorized scope. Internal references are never HTTP views. */
public record AuthorizedDevice(
        String id,
        String kind,
        String name,
        String installAddress,
        boolean observationOnly,
        String controlRef,
        String officeDeviceRef,
        String hubDeviceId,
        String smartAreaCode,
        List<List<String>> locationPaths,
        List<String> officeIds,
        Map<String, Object> metadata) {

    /** Compatibility constructor for existing callers and fixtures. */
    public AuthorizedDevice(String id, String kind, String name, String installAddress, boolean observationOnly) {
        this(id, kind, name, installAddress, observationOnly, null, null, null, null, List.of(), List.of(), Map.of());
    }

    /**
     * Returns the adapter provenance keys used by the Runtime catalog fingerprint.
     * Older adapters did not carry this field, so the normalized kind is a safe,
     * deterministic fallback rather than an empty binding.
     */
    public List<String> sourceKeys() {
        Object value = metadata.get("sourceKeys");
        if (value instanceof List<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .filter(item -> !item.isBlank()).distinct().sorted().toList();
        }
        return List.of("microbrain." + kind);
    }

    /**
     * Fingerprint of the non-secret catalog fields used to authorize a persisted
     * task against a fresh scope. It intentionally excludes mutable observation
     * state and raw control action payloads.
     */
    public String catalogFingerprint() {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("id", id);
        fields.put("kind", kind);
        fields.put("name", name);
        fields.put("installAddress", installAddress);
        fields.put("locationPaths", locationPaths);
        fields.put("officeDeviceRef", officeDeviceRef);
        fields.put("officeIds", officeIds);
        fields.put("sourceKeys", sourceKeys());
        return sha256(canonical(fields));
    }

    /**
     * Stronger binding fingerprint for control revalidation. Unlike the catalog
     * fingerprint it includes every non-secret physical/control binding field
     * and normalized adapter metadata.
     */
    public String bindingFingerprint() {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("catalog", catalogFingerprint());
        fields.put("controlRef", controlRef);
        fields.put("hubDeviceId", hubDeviceId);
        fields.put("smartAreaCode", smartAreaCode);
        fields.put("metadata", metadata);
        return sha256(canonical(fields));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            return sorted.entrySet().stream()
                    .map(entry -> quote(entry.getKey()) + ":" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuthorizedDevice::canonical)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return quote(String.valueOf(value));
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            switch (item) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (item < 0x20) result.append(String.format("\\u%04x", (int) item));
                    else result.append(item);
                }
            }
        }
        return result.append('"').toString();
    }
    public AuthorizedDevice {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Device id is required");
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Device kind is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Device name is required");
        installAddress = installAddress == null ? "" : installAddress;
        locationPaths = locationPaths == null ? List.of() : locationPaths.stream()
                .map(path -> List.copyOf(path == null ? List.of() : path)).toList();
        officeIds = officeIds == null ? List.of() : officeIds.stream()
                .filter(officeId -> officeId != null && !officeId.isBlank()).distinct().toList();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
