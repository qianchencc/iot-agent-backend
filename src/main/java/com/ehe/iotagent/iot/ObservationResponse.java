package com.ehe.iotagent.iot;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/**
 * A read result retains coverage semantics instead of collapsing missing or stale
 * readings into a successful empty value. The payload is the route-shaped JSON
 * object supplied by the upstream protocol fixture or adapter.
 */
public record ObservationResponse(
        ObservationStatusEnum status,
        Map<String, Object> payload,
        List<ObservationFailure> failures,
        boolean coverageComplete) {

    public ObservationResponse {
        Objects.requireNonNull(status, "status");
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        failures = failures == null ? List.of() : List.copyOf(failures);
        if (status == ObservationStatusEnum.AVAILABLE && !failures.isEmpty()) {
            throw new IllegalArgumentException("available observation cannot carry failures");
        }
    }

    public static ObservationResponse available(Map<String, Object> payload) {
        return new ObservationResponse(ObservationStatusEnum.AVAILABLE, payload, List.of(), true);
    }

    public static ObservationResponse empty(Map<String, Object> payload) {
        return new ObservationResponse(ObservationStatusEnum.EMPTY, payload, List.of(), true);
    }

    public static ObservationResponse partial(
            Map<String, Object> payload,
            List<ObservationFailure> failures,
            boolean coverageComplete) {
        return new ObservationResponse(ObservationStatusEnum.PARTIAL, payload, failures, coverageComplete);
    }
}
