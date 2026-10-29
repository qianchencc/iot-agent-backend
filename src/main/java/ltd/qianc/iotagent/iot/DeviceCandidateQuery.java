package ltd.qianc.iotagent.iot;

import java.util.List;

/** Runtime-compatible candidate query; scope is always intersected with server scope. */
public record DeviceCandidateQuery(
        String kind,
        List<String> deviceIds,
        String scopeText,
        List<String> scopeTerms,
        List<String> nameHints,
        String deviceText) {

    public DeviceCandidateQuery {
        kind = kind == null || kind.isBlank() ? "unknown" : kind.trim();
        deviceIds = immutable(deviceIds);
        scopeTerms = immutable(scopeTerms);
        nameHints = immutable(nameHints);
        if (scopeText != null) scopeText = scopeText.trim();
        if (deviceText != null) deviceText = deviceText.trim();
    }

    private static List<String> immutable(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
