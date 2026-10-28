package ltd.qianc.iotagent.iot;

import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.auth.PrincipalInfo;

/**
 * Identity captured for one gateway call. It is deliberately explicit so a device
 * scope cannot be loaded without selecting its Office or Company authorization domain.
 */
public record RequestIdentity(String authorization, String tenantCode, IotSource source) {

    public RequestIdentity {
        if (authorization == null || authorization.isBlank()) {
            throw new IllegalArgumentException("Authorization is required");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("Tenantcode is required");
        }
        Objects.requireNonNull(source, "source");
    }

    public static RequestIdentity fromPrincipal(IotSource source) {
        PrincipalInfo principal = PrincipalContext.getPrincipalInfo();
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source);
    }

    /** Exact customer gateway headers; source is a local authorization boundary, not a forwarded credential. */
    public Map<String, String> gatewayHeaders() {
        return Map.of("Authorization", authorization, "Tenantcode", tenantCode);
    }
}
