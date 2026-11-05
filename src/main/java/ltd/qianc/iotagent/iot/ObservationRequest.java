package ltd.qianc.iotagent.iot;

import java.util.Map;
import java.util.Objects;

/** Server-built request; query strings are validated by the HTTP controller first. */
public record ObservationRequest(
        RequestIdentity identity,
        ObservationOperationEnum operation,
        Map<String, String> parameters) {
    public ObservationRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operation, "operation");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
