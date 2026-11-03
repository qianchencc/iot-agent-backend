package ltd.qianc.iotagent.iot;

import java.util.List;
import reactor.core.publisher.Mono;

/** Gateway boundary used by Agent tools; callers own subscription cancellation. */
public interface IotAdapter {

    Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity);
    /** Company safety metadata is a narrower read-only scope than the full catalog. */
    default Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity identity) {
        return loadAuthorizedDeviceScope(identity);
    }
    /** Video authorization is a separate Company monitor grant, not the full device catalog. */
    default Mono<AuthorizedDeviceScope> loadAuthorizedVideoScope(RequestIdentity identity) {
        return loadAuthorizedDeviceScope(identity);
    }
    /**
     * Company meter authorization only. The WebClient implementation reads the
     * energy family alone so meter history is not blocked by unrelated catalog
     * families; adapters without a narrower read may reuse the full scope.
     */
    default Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
        return loadAuthorizedDeviceScope(identity);
    }

    /** Runtime-compatible candidate discovery, always intersected with server scope. */
    default Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        return Mono.error(new UpstreamUnavailableException("物联设备发现适配器未接入"));
    }

    /**
     * Discovers and persists a candidate set bound to the current conversation.
     * Implementations must fail closed rather than return an unpersisted set.
     */
    default Mono<DeviceCandidateSet> findDeviceCandidates(
            RequestIdentity identity, DeviceCandidateQuery query, DeviceCandidateBinding binding) {
        return findDeviceCandidates(identity, query);
    }

    /** Resolves the complete persisted membership, bound to the discovering identity and conversation. */
    default Mono<DeviceCandidateRecord> resolveDeviceCandidateSet(
            RequestIdentity identity, String candidateSetId, String conversationId) {
        return Mono.error(new UpstreamUnavailableException("设备候选集读取适配器未接入"));
    }

    /** Real Office membership used to validate a server-bound focus region. */
    default Mono<List<OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        return Mono.error(new UpstreamUnavailableException("Office 区域目录适配器未接入"));
    }

    /** Runtime-compatible read-only state operation. */
    default Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        return Mono.error(new UpstreamUnavailableException("物联设备状态适配器未接入"));
    }

    /**
     * State read bound to the candidate sets the model actually received. Every
     * requested device must be a fixed member of its referenced, persisted set --
     * an authorized intersection alone is not enough.
     */
    default Mono<DeviceStateReadResult> readDeviceStates(
            RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        List<String> ids = request.candidateSets().stream()
                .flatMap(selection -> selection.deviceIds().stream()).toList();
        return readDeviceStates(identity, ids);
    }

    /**
     * Runtime-compatible Company observation-state read keyed by device id. The
     * implementation must resolve each environment sensor through its own
     * `realTimeData` action with the site shadow as fallback, read the
     * energy realtime table once for all meters, and return null state for any
     * device without evidence. It must never invent a history row as state.
     */
    default Mono<DeviceStateReadResult> readObservationStates(
            RequestIdentity identity, List<AuthorizedDevice> devices) {
        return Mono.error(new UpstreamUnavailableException("物联观察状态适配器未接入"));
    }

    /** Runtime-compatible read-only capability operation. */
    default Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, DeviceCapabilityRequest request) {
        return Mono.error(new UpstreamUnavailableException("物联设备能力适配器未接入"));
    }

    /**
     * Capability inspection bound to the discovering conversation. Implementations
     * must reject candidate-set references that do not belong to that conversation.
     */
    default Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        return inspectDeviceCapabilities(identity, request);
    }
    /** Opens a short-lived live stream from the current authorized video scope. */
    default Mono<VideoStream> openVideoStream(RequestIdentity identity, String deviceId) {
        return Mono.error(new UpstreamUnavailableException("物联视频取流适配器未接入"));
    }

    /** Opens a short-lived playback stream from the current authorized video scope. */
    default Mono<VideoStream> openVideoPlayback(
            RequestIdentity identity, String deviceId, VideoPlaybackWindow window) {
        return Mono.error(new UpstreamUnavailableException("物联录像取流适配器未接入"));
    }
    /** Validates the current video scope and prepares a playback card without fetching a URL. */
    default Mono<VideoPlaybackPreparation> prepareVideoPlayback(
            RequestIdentity identity, String deviceId, VideoPlaybackWindow window) {
        return Mono.error(new UpstreamUnavailableException("物联视频准备适配器未接入"));
    }
}
