package com.ehe.iotagent.iot;

import java.util.List;

/**
 * Runtime-compatible candidate query. Scope is always intersected with server
 * scope. {@code regionId} is the server-bound Office focus region; it is never
 * derived from model text and is ignored for Company queries.
 */
public record DeviceCandidateQuery(
        String kind,
        List<String> deviceIds,
        String scopeText,
        List<String> scopeTerms,
        List<String> nameHints,
        String deviceText,
        String regionId) {

    public DeviceCandidateQuery {
        kind = kind == null || kind.isBlank() ? "unknown" : kind.trim();
        deviceIds = immutable(deviceIds);
        scopeTerms = immutable(scopeTerms);
        nameHints = immutable(nameHints);
        if (scopeText != null) scopeText = scopeText.trim();
        if (deviceText != null) deviceText = deviceText.trim();
        if (regionId != null && regionId.isBlank()) regionId = null;
    }

    private static List<String> immutable(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
