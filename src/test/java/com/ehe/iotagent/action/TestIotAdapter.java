package com.ehe.iotagent.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/** Public-seam fixture: state reads are supplied explicitly, never inferred from commands. */
final class TestIotAdapter implements IotAdapter {
    private final List<AuthorizedDevice> devices;
    private final Function<RequestIdentity, Map<String, Object>> states;
    private final List<com.ehe.iotagent.iot.OfficeRegion> regions;
    String observedAt = "2026-10-30T09:00:00Z";
    Map<String, Object> cache = Map.of();

    TestIotAdapter(List<AuthorizedDevice> devices, Function<RequestIdentity, Map<String, Object>> states) {
        this(devices, states, List.of());
    }

    TestIotAdapter(List<AuthorizedDevice> devices, Function<RequestIdentity, Map<String, Object>> states,
                   List<com.ehe.iotagent.iot.OfficeRegion> regions) {
        this.devices = List.copyOf(devices);
        this.states = states;
        this.regions = List.copyOf(regions);
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices));
    }

    @Override
    public Mono<List<com.ehe.iotagent.iot.OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        return Mono.just(regions);
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        Map<String, Object> values = new LinkedHashMap<>(states.apply(identity));
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String id : deviceIds) selected.put(id, values.get(id));
        return Mono.just(new DeviceStateReadResult(selected, observedAt, List.of(), Map.of(), null, null, cache));
    }
}
