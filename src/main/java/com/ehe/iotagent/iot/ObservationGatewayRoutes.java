package com.ehe.iotagent.iot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit customer read-only routes. A missing route is an unavailable capability;
 * callers must not infer a customer endpoint from an operation name.
 */
public record ObservationGatewayRoutes(Map<ObservationOperationEnum, String> paths) {
    public ObservationGatewayRoutes {
        Objects.requireNonNull(paths, "paths");
        EnumMap<ObservationOperationEnum, String> copy = new EnumMap<>(ObservationOperationEnum.class);
        paths.forEach((operation, path) -> {
            if (operation == null || path == null || path.isBlank() || !path.startsWith("/")) {
                throw new IllegalArgumentException("observation route must be an absolute path");
            }
            if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
                throw new IllegalArgumentException("observation route must not contain query or fragment");
            }
            if (path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
                if (!isLedgerTemplate(operation, path)) {
                    throw new IllegalArgumentException("observation route template is not supported");
                }
            }
            copy.put(operation, path);
        });
        paths = Map.copyOf(copy);
    }

    public String path(ObservationOperationEnum operation) {
        Objects.requireNonNull(operation, "operation");
        return paths.get(operation);
    }

    /** Resolve the only Runtime-derived observation route template. */
    public String resolvedPath(ObservationOperationEnum operation, Map<String, String> parameters) {
        String path = path(operation);
        if (path == null || !path.contains("{ledger}")) return path;
        String ledger = parameters == null ? null : parameters.get("ledger");
        if (ledger == null && operation == ObservationOperationEnum.SAFETY_EVENTS) {
            String type = parameters == null ? null : parameters.get("type");
            ledger = "water".equals(type) ? "flooding" : type;
            if (ledger == null) ledger = "gas";
        }
        if (ledger == null || !java.util.Set.of("gas", "smoke", "flooding").contains(ledger)) return null;
        return path.replace("{ledger}", ledger);
    }

    private static boolean isLedgerTemplate(ObservationOperationEnum operation, String path) {
        return (operation == ObservationOperationEnum.SAFETY_EVENTS
                || operation == ObservationOperationEnum.SAFETY_EVENT
                || operation == ObservationOperationEnum.SAFETY_EVENT_CAMERAS)
                && path.indexOf("{ledger}") >= 0
                && path.indexOf('{') == path.indexOf("{ledger}")
                && path.indexOf('}') == path.indexOf("{ledger}") + "{ledger}".length() - 1;
    }
}
