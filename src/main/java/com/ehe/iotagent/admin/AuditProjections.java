package com.ehe.iotagent.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared whitelist projection for device-action rows shown in the admin audit
 * console. Used by the audit controller for conversation draft payloads and by
 * the task module's proposal provider for durable task definitions, so both
 * surfaces render the same safe shape. Credential-shaped text is redacted and
 * every string is bounded before it can reach the console.
 */
public final class AuditProjections {
    private static final int MAX_TEXT = 256;
    private static final List<String> ACTION_FIELDS = List.of(
            "id", "deviceId", "kind", "name", "deviceName", "location", "regionId",
            "roomName", "areaName", "satisfied", "command", "state");
    private static final Pattern BEARER_TEXT = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{4,}");
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|passwd|"
                    + "authorization|credential|token)\\b(['\"\\s:=]+)(\\S+)");

    private AuditProjections() { }

    /** Single action row projection shared by audit payloads and task proposals. */
    public static Map<String, Object> projectAction(Object action) {
        if (!(action instanceof Map<?, ?> item)) return null;
        Map<String, Object> row = new LinkedHashMap<>();
        for (String field : ACTION_FIELDS) {
            Object value = item.get(field);
            if (value instanceof Map<?, ?> values && ("command".equals(field) || "state".equals(field))) {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (String name : List.of("power", "mode", "temperature", "fanSpeed", "closeOrOpen",
                        "openOrClose", "on", "targetTemperature", "reportedAt", "status")) {
                    Object entry = scalar(values.get(name), MAX_TEXT);
                    if (entry != null) fields.put(name, entry);
                }
                row.put(field, fields);
            } else {
                Object entry = scalar(value, MAX_TEXT);
                if (entry != null) row.put(field, entry);
            }
        }
        return row.isEmpty() ? null : row;
    }

    /** Credential-shaped text never passes through, even inside an otherwise public reply. */
    public static String redact(String value) {
        return CREDENTIAL_TEXT.matcher(BEARER_TEXT.matcher(value).replaceAll("Bearer ***"))
                .replaceAll("$1$2***");
    }

    public static String bound(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…（已截断）";
    }

    public static Object scalar(Object value, int max) {
        if (value instanceof String text) return bound(redact(text), max);
        return value instanceof Number || value instanceof Boolean ? value : null;
    }
}
