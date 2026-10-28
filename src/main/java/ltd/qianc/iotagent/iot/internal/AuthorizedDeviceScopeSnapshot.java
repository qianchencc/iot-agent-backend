package ltd.qianc.iotagent.iot.internal;

import java.util.List;
import java.util.Objects;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;

public final class AuthorizedDeviceScopeSnapshot implements AuthorizedDeviceScope {

    private final RequestIdentity identity;
    private final List<AuthorizedDevice> devices;

    public AuthorizedDeviceScopeSnapshot(RequestIdentity identity, List<AuthorizedDevice> devices) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.devices = List.copyOf(Objects.requireNonNull(devices, "devices"));
    }

    @Override
    public RequestIdentity identity() {
        return identity;
    }

    @Override
    public IotSource source() {
        return identity.source();
    }

    @Override
    public List<AuthorizedDevice> devices() {
        return devices;
    }
}
