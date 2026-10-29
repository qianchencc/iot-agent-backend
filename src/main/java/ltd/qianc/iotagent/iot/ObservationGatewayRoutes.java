package ltd.qianc.iotagent.iot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit customer read-only routes. A missing route is an unavailable capability;
 * callers must not infer a customer endpoint from an operation name.
 */
public record ObservationGatewayRoutes(Map<ObservationOperation, String> paths) {
    public ObservationGatewayRoutes {
        Objects.requireNonNull(paths, "paths");
        EnumMap<ObservationOperation, String> copy = new EnumMap<>(ObservationOperation.class);
        paths.forEach((operation, path) -> {
            if (operation == null || path == null || path.isBlank() || !path.startsWith("/")) {
                throw new IllegalArgumentException("observation route must be an absolute path");
            }
            if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('{') >= 0 || path.indexOf('}') >= 0) {
                throw new IllegalArgumentException("observation route must not contain query, fragment, or template");
            }
            copy.put(operation, path);
        });
        paths = Map.copyOf(copy);
    }

    public String path(ObservationOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return paths.get(operation);
    }
}
