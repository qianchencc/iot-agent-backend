package ltd.qianc.iotagent.iot.internal;

import java.util.Objects;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import reactor.core.publisher.Mono;

/** No local identity, device list, or successful empty scope is fabricated. */
public final class FailClosedIotAdapter implements IotAdapter {

    private final String reason;

    public FailClosedIotAdapter(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return Mono.error(new UpstreamUnavailableException(reason));
    }
}
