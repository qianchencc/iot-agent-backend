package ltd.qianc.iotagent.iot;

import java.util.List;
import reactor.core.publisher.Mono;

/** Gateway boundary used by Agent tools; callers own subscription cancellation. */
public interface IotAdapter {

    Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity);

    /** Runtime-compatible Office candidate discovery, always intersected with server scope. */
    default Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        return Mono.error(new UpstreamUnavailableException("物联设备发现适配器未接入"));
    }

    /** Runtime-compatible read-only state operation. */
    default Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        return Mono.error(new UpstreamUnavailableException("物联设备状态适配器未接入"));
    }

    /** Runtime-compatible read-only capability operation. */
    default Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, DeviceCapabilityRequest request) {
        return Mono.error(new UpstreamUnavailableException("物联设备能力适配器未接入"));
    }
}
