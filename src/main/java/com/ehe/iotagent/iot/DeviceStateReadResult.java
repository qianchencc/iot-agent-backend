package com.ehe.iotagent.iot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only state projection; unknown state is represented by null, never by false. */
public record DeviceStateReadResult(Map<String, Object> states, String observedAt,
                                    List<Map<String, Object>> blocks,
                                    Map<String, String> observedAtByDevice,
                                    ObservationStatusEnum status, List<String> missingDeviceIds,
                                    Map<String, Object> cache) {
    public DeviceStateReadResult(Map<String, Object> states, String observedAt) {
        this(states, observedAt, List.of(), Map.of());
    }

    public DeviceStateReadResult(Map<String, Object> states, String observedAt,
                                 List<Map<String, Object>> blocks) {
        this(states, observedAt, blocks, Map.of());
    }

    public DeviceStateReadResult(Map<String, Object> states, String observedAt,
                                 List<Map<String, Object>> blocks, Map<String, String> observedAtByDevice) {
        this(states, observedAt, blocks, observedAtByDevice, null, null, Map.of());
    }

    public DeviceStateReadResult {
        Map<String, Object> stateCopy = new LinkedHashMap<>(states == null ? Map.of() : states);
        states = Collections.unmodifiableMap(stateCopy);
        observedAt = observedAt == null || observedAt.isBlank() ? null : observedAt;
        List<Map<String, Object>> blockCopy = new ArrayList<>();
        if (blocks != null) {
            for (Map<String, Object> block : blocks) {
                blockCopy.add(Collections.unmodifiableMap(new LinkedHashMap<>(block == null ? Map.of() : block)));
            }
        }
        blocks = Collections.unmodifiableList(blockCopy);
        Map<String, String> observedCopy = new LinkedHashMap<>();
        if (observedAtByDevice != null) {
            observedAtByDevice.forEach((id, at) -> {
                if (id != null && !id.isBlank() && at != null && !at.isBlank()) observedCopy.put(id, at);
            });
        }
        observedAtByDevice = Collections.unmodifiableMap(observedCopy);
        missingDeviceIds = missingDeviceIds == null
                ? states.entrySet().stream().filter(entry -> entry.getValue() == null).map(Map.Entry::getKey).toList()
                : List.copyOf(missingDeviceIds);
        if (status == null) {
            boolean missingTime = observedAt == null && !observedAtByDevice.keySet().containsAll(states.keySet());
            status = states.isEmpty() ? ObservationStatusEnum.EMPTY
                    : missingDeviceIds.size() == states.size() ? ObservationStatusEnum.UNAVAILABLE
                    : !missingDeviceIds.isEmpty() || missingTime ? ObservationStatusEnum.PARTIAL
                    : ObservationStatusEnum.AVAILABLE;
        }
        cache = Collections.unmodifiableMap(new LinkedHashMap<>(cache == null ? Map.of() : cache));
    }

    /** Existing control-preparation freshness rule, also required by local-only execution preflight. */
    public boolean currentForControl(String deviceId, java.time.Instant now) {
        if ("stale".equals(cache.get("status"))) return false;
        String at = observedAtByDevice.getOrDefault(deviceId, observedAt);
        if (at == null) return false;
        try {
            java.time.Instant sample = java.time.Instant.parse(at);
            return !sample.isAfter(now) && !sample.isBefore(now.minusSeconds(60));
        } catch (java.time.format.DateTimeParseException unknownTime) {
            return false;
        }
    }
}
