package com.ehe.iotagent.iot.internal;

import java.util.Objects;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.iot.UpstreamUnavailableException;
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
