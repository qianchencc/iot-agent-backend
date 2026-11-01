package ltd.qianc.iotagent.iot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only state projection; unknown state is represented by null, never by false. */
public record DeviceStateReadResult(Map<String, Object> states, String observedAt,
                                    List<Map<String, Object>> blocks,
                                    Map<String, String> observedAtByDevice) {
    public DeviceStateReadResult(Map<String, Object> states, String observedAt) {
        this(states, observedAt, List.of(), Map.of());
    }

    public DeviceStateReadResult(Map<String, Object> states, String observedAt,
                                 List<Map<String, Object>> blocks) {
        this(states, observedAt, blocks, Map.of());
    }

    public DeviceStateReadResult {
        Map<String, Object> stateCopy = new LinkedHashMap<>(states == null ? Map.of() : states);
        states = Collections.unmodifiableMap(stateCopy);
        observedAt = observedAt == null || observedAt.isBlank() ? Instant.now().toString() : observedAt;
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
    }
}
