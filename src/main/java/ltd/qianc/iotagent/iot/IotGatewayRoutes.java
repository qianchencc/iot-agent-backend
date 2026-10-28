package ltd.qianc.iotagent.iot;

import java.util.Objects;

/** Explicit upstream paths; no guessed production endpoint is used by default. */
public record IotGatewayRoutes(String officeScopePath, String companyScopePath) {

    public IotGatewayRoutes {
        officeScopePath = requirePath(officeScopePath, "officeScopePath");
        companyScopePath = requirePath(companyScopePath, "companyScopePath");
    }

    public String path(IotSource source) {
        Objects.requireNonNull(source, "source");
        return source == IotSource.OFFICE ? officeScopePath : companyScopePath;
    }

    private static String requirePath(String value, String name) {
        if (value == null || value.isBlank() || !value.startsWith("/")) {
            throw new IllegalArgumentException(name + " must be an absolute path");
        }
        return value;
    }
}
