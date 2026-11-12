package com.ehe.iotagent.iot.internal;

import java.util.List;
import java.util.Objects;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;

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
    public IotSourceEnum source() {
        return identity.source();
    }

    @Override
    public List<AuthorizedDevice> devices() {
        return devices;
    }
}
