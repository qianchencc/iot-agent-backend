package ltd.qianc.iotagent.iot.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/** Local Runtime-derived authorization fixture; it has no network transport. */
public final class FixtureIotAdapter implements IotAdapter {
    private static final List<AuthorizedDevice> DEVICES = List.of(
            new AuthorizedDevice("80536a7e04f979af27820f466933fd1e", "ac", "院长室空调", "数智工程研究院院长室", false),
            new AuthorizedDevice("704c05c5a95f6dc3a4e0f7e3421589f", "ac", "会议室空调", "数智工程研究院院长室", false),
            new AuthorizedDevice("e7aa7dba90ed4a21bb70f0bcca8e49b2", "lighting", "院长室照明", "数智工程研究院院长室", false),
            new AuthorizedDevice("e95111e0db199dca48bc07091b62eb11", "lighting", "会议区照明", "数智工程研究院院长室", false),
            new AuthorizedDevice("ee04e47d6fbe19efa2a4a74ce2e82e9d", "lighting", "走廊照明", "数智工程研究院院长室", false),
            new AuthorizedDevice("a170f138cac7b6074c8cb9cfda475f35", "access", "院长室门禁", "数智工程研究院院长室", false),
            new AuthorizedDevice("f2e2d218b21b7477c8d782357174092f", "access", "会议室门禁", "数智工程研究院院长室", false));

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        return Mono.just(new FixtureScope(identity, DEVICES));
    }

    @Override
    public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        return loadAuthorizedDeviceScope(identity).map(scope -> {
            List<AuthorizedDevice> matches = scope.devices().stream()
                    .filter(device -> device.kind().equalsIgnoreCase(query.kind()))
                    .filter(device -> query.deviceIds().isEmpty() || query.deviceIds().contains(device.id()))
                    .filter(device -> query.deviceText() == null || (device.name() + " " + device.installAddress()).contains(query.deviceText()))
                    .toList();
            return new DeviceCandidateSet(null, query, matches, true, matches.size());
        });
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        return loadAuthorizedDeviceScope(identity).map(scope -> {
            Map<String, Object> states = new LinkedHashMap<>();
            for (String id : deviceIds == null ? List.<String>of() : deviceIds) {
                AuthorizedDevice device = scope.device(id).orElseThrow(() -> new IllegalArgumentException("device outside fixture scope"));
                states.put(id, switch (device.kind()) {
                    case "lighting" -> Map.of("power", "off");
                    case "access" -> Map.of("locked", true);
                    case "ac" -> Map.of("power", "off", "temperature", 24);
                    default -> null;
                });
            }
            return new DeviceStateReadResult(states, Instant.now().toString());
        });
    }

    @Override
    public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request) {
        return loadAuthorizedDeviceScope(identity).map(scope -> {
            List<DeviceCapabilityInspection.DeviceCapability> devices = new ArrayList<>();
            for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
                for (String id : selection.deviceIds()) {
                    AuthorizedDevice device = scope.device(id).orElseThrow(() -> new IllegalArgumentException("device outside fixture scope"));
                    List<Map<String, Object>> commands = switch (device.kind()) {
                        case "lighting" -> List.of(Map.of("power", "on"), Map.of("power", "off"));
                        case "access" -> List.of(Map.of("controlType", 2));
                        default -> List.of();
                    };
                    DeviceCapabilityInspection.MapParameters parameters = "ac".equals(device.kind())
                            ? new DeviceCapabilityInspection.MapParameters(16, 30, 1,
                            List.of("auto", "cool", "heat", "dry", "fan"), List.of("low", "high", "auto")) : null;
                    devices.add(new DeviceCapabilityInspection.DeviceCapability(id, device.kind(), commands, parameters));
                }
            }
            return new DeviceCapabilityInspection(request.candidateSets().stream()
                    .map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(), devices);
        });
    }

    private record FixtureScope(RequestIdentity identity, List<AuthorizedDevice> devices) implements AuthorizedDeviceScope {
        @Override
        public IotSource source() {
            return identity.source();
        }
    }
}
