package ltd.qianc.iotagent.iot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only state projection; unknown state is represented by null, never by false. */
public record DeviceStateReadResult(Map<String, Object> states, String observedAt) {
    public DeviceStateReadResult {
        Map<String, Object> copy = new LinkedHashMap<>(states == null ? Map.of() : states);
        states = Collections.unmodifiableMap(copy);
        observedAt = observedAt == null || observedAt.isBlank() ? Instant.now().toString() : observedAt;
    }
}
