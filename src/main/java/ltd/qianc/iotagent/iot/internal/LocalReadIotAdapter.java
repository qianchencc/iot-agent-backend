package ltd.qianc.iotagent.iot.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceDirectory;
import ltd.qianc.iotagent.iot.DeviceDirectoryBatch;
import ltd.qianc.iotagent.iot.DeviceObservations;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.VideoPlaybackPreparation;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.iot.VideoStream;
import reactor.core.publisher.Mono;

/** Shared HTTP/Agent/business read boundary. Only named collection and real-action methods reach transport. */
public final class LocalReadIotAdapter implements IotAdapter {
    private final IotAdapter source;
    private final Supplier<DeviceDirectory> directories;
    private final Supplier<DeviceObservations> observations;
    private final DeviceCandidateSetStore candidates;

    public LocalReadIotAdapter(IotAdapter source, Supplier<DeviceDirectory> directories,
            Supplier<DeviceObservations> observations, DeviceCandidateSetStore candidates) {
        this.source = Objects.requireNonNull(source);
        this.directories = Objects.requireNonNull(directories);
        this.observations = Objects.requireNonNull(observations);
        this.candidates = Objects.requireNonNull(candidates);
    }

    @Override public Mono<DeviceDirectoryBatch> readDirectory(RequestIdentity identity) {
        return Mono.fromCallable(() -> directory().loadBatch(identity));
    }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        return readDirectory(identity).map(DeviceDirectoryBatch::scope);
    }
    @Override public Mono<List<OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        if (identity.source() != IotSourceEnum.OFFICE) {
            return Mono.error(new BusinessException("OFFICE_REGION_UNAVAILABLE", "Office 区域目录仅支持Office来源", 404));
        }
        return readDirectory(identity).map(DeviceDirectoryBatch::regions);
    }
    @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        return findDeviceCandidates(identity, query, null);
    }
    @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query, DeviceCandidateBinding binding) {
        return readDirectory(identity).map(batch -> DeviceCandidates.find(batch.forRegion(query.regionId()), query, binding, candidates));
    }
    @Override public Mono<DeviceCandidateRecord> resolveDeviceCandidateSet(RequestIdentity identity, String id, String conversationId) {
        return readDirectory(identity).map(batch -> DeviceCandidates.resolve(candidates, identity, id, conversationId, batch));
    }
    @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> ids) {
        return Mono.fromCallable(() -> {
            DeviceObservations current = observations.get();
            if (current == null) throw new BusinessException("DEVICE_STATE_UNAVAILABLE", "本地设备状态未配置", 503);
            return current.read(identity, ids);
        });
    }
    @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集会话绑定缺失"));
        }
        return Mono.fromCallable(() -> selected(identity, request, conversationId).stream().map(AuthorizedDevice::id).toList())
                .flatMap(ids -> readDeviceStates(identity, ids));
    }
    @Override public Mono<DeviceStateReadResult> readObservationStates(RequestIdentity identity, List<AuthorizedDevice> devices) {
        if (identity.source() != IotSourceEnum.COMPANY) {
            return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "观察状态仅支持Company来源", 404));
        }
        List<String> ids = (devices == null ? List.<AuthorizedDevice>of() : devices).stream()
                .filter(device -> device != null && ("energy".equalsIgnoreCase(device.kind()) || "environment".equalsIgnoreCase(device.kind())))
                .map(AuthorizedDevice::id).distinct().toList();
        return ids.isEmpty() ? readDirectory(identity).map(batch -> new DeviceStateReadResult(Map.of(), null)) : readDeviceStates(identity, ids);
    }
    @Override public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request) {
        return inspectDeviceCapabilities(identity, request, null);
    }
    @Override public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        return Mono.fromCallable(() -> {
            List<AuthorizedDevice> devices = selected(identity, request, conversationId);
            return new DeviceCapabilityInspection(request.candidateSets().stream()
                    .map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(),
                    devices.stream().map(DeviceCapabilities::describe).toList());
        });
    }

    private List<AuthorizedDevice> selected(RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        DeviceCandidates.validate(request);
        DeviceDirectoryBatch batch = directory().loadBatch(identity);
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (var selection : request.candidateSets()) {
            if (conversationId != null && !conversationId.isBlank()) {
                DeviceCandidateRecord record = DeviceCandidates.resolve(candidates, identity, selection.candidateSetId(), conversationId, batch);
                var members = new HashSet<String>();
                for (AuthorizedDevice member : record.members()) members.add(member.id());
                if (!members.containsAll(selection.deviceIds())) {
                    throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备不属于所引用的候选集", 403);
                }
            }
            for (String id : selection.deviceIds()) {
                devices.add(batch.scope().device(id).orElseThrow(() -> new BusinessException("DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403)));
            }
        }
        return devices;
    }
    private DeviceDirectory directory() {
        DeviceDirectory directory = directories.get();
        if (directory == null) throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "本地设备目录未配置", 503);
        return directory;
    }

    @Override public Mono<DeviceDirectoryBatch> collectDirectory(RequestIdentity identity) { return source.collectDirectory(identity); }
    @Override public Mono<DeviceStateReadResult> collectCurrentStates(RequestIdentity identity, AuthorizedDeviceScope scope) {
        return source.collectCurrentStates(identity, scope);
    }
    @Override public Mono<String> readCreatorDisplayName(RequestIdentity identity) { return source.readCreatorDisplayName(identity); }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity identity) { return source.loadAuthorizedSafetyScope(identity); }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedVideoScope(RequestIdentity identity) { return source.loadAuthorizedVideoScope(identity); }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
        return Mono.fromCallable(() -> directory().energyHistory().authorizedScope(identity));
    }
    @Override public Mono<VideoStream> openVideoStream(RequestIdentity identity, String id) { return source.openVideoStream(identity, id); }
    @Override public Mono<VideoStream> openVideoPlayback(RequestIdentity identity, String id, VideoPlaybackWindow window) {
        return source.openVideoPlayback(identity, id, window);
    }
    @Override public Mono<VideoPlaybackPreparation> prepareVideoPlayback(RequestIdentity identity, String id, VideoPlaybackWindow window) {
        return source.prepareVideoPlayback(identity, id, window);
    }
}
