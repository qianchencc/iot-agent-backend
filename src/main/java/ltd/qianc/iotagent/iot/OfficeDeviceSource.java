package ltd.qianc.iotagent.iot;

import java.util.Map;
import java.util.Objects;

/** A configured, read-only customer directory source used to build Office scope. */
public record OfficeDeviceSource(
        String key,
        String kind,
        String path,
        Map<String, String> params,
        boolean requiresOfficeContext) {

    public OfficeDeviceSource {
        key = requireText(key, "key");
        kind = requireText(kind, "kind");
        path = requirePath(path);
        params = Map.copyOf(Objects.requireNonNull(params, "params"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String requirePath(String value) {
        if (value == null || value.isBlank() || !value.startsWith("/")) {
            throw new IllegalArgumentException("path must be an absolute path");
        }
        return value;
    }
}
