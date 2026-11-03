package ltd.qianc.iotagent.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/** Public-seam fixture: state reads are supplied explicitly, never inferred from commands. */
final class TestIotAdapter implements IotAdapter {
    private final List<AuthorizedDevice> devices;
    private final Function<RequestIdentity, Map<String, Object>> states;
    private final List<ltd.qianc.iotagent.iot.OfficeRegion> regions;

    TestIotAdapter(List<AuthorizedDevice> devices, Function<RequestIdentity, Map<String, Object>> states) {
        this(devices, states, List.of());
    }

    TestIotAdapter(List<AuthorizedDevice> devices, Function<RequestIdentity, Map<String, Object>> states,
                   List<ltd.qianc.iotagent.iot.OfficeRegion> regions) {
        this.devices = List.copyOf(devices);
        this.states = states;
        this.regions = List.copyOf(regions);
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices));
    }

    @Override
    public Mono<List<ltd.qianc.iotagent.iot.OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        return Mono.just(regions);
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        Map<String, Object> values = new LinkedHashMap<>(states.apply(identity));
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String id : deviceIds) selected.put(id, values.get(id));
        return Mono.just(new DeviceStateReadResult(selected, "2026-10-30T09:00:00Z"));
    }
}
