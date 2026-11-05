package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import reactor.core.publisher.Mono;

/**
 * Agent read view over the existing backend read entries. Actions and uncached
 * capabilities remain on the source adapter; this decorator adds no cache key.
 */
public final class AgentReadIotAdapter implements IotAdapter {
    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(120);
    private final IotAdapter source;
    private final ReadSnapshots snapshots;
    private final ObjectMapper mapper;

    public AgentReadIotAdapter(IotAdapter source, ReadSnapshots snapshots, ObjectMapper mapper) {
        this.source = Objects.requireNonNull(source, "source");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        if (identity.source() != ltd.qianc.iotagent.iot.IotSourceEnum.COMPANY) {
            return source.loadAuthorizedDeviceScope(identity);
        }
        return Mono.fromCallable(() -> {
            Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "COMPANY_DEVICES", Map.of(),
                    ReadSnapshots.PolicyEnum.CURRENT, () -> companyDevices(identity));
            requireReady(body, "IOT_SCOPE_PREPARING", "当前设备目录准备中，请稍后重试");
            return scope(identity, body);
        });
    }

    @Override public Mono<List<OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        return Mono.fromCallable(() -> {
            Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "OFFICE_REGION_CATALOG", Map.of(),
                    ReadSnapshots.PolicyEnum.HISTORICAL, () -> officeRegions(identity));
            requireReady(body, "REGION_PREPARING", "当前区域目录准备中，请稍后重试");
            Object raw = body.get("regions");
            if (!(raw instanceof List<?>)) throw new BusinessException(
                    "REGION_UNAVAILABLE", "当前区域目录格式无效", 503);
            return mapper.convertValue(raw, new TypeReference<List<OfficeRegion>>() { });
        });
    }

    @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        return Mono.fromCallable(() -> {
            Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "DEVICE_STATES",
                    Map.of("deviceIds", deviceIds), ReadSnapshots.PolicyEnum.CURRENT,
                    () -> deviceStates(identity, deviceIds));
            requireReady(body, "DEVICE_STATE_PREPARING", "设备状态准备中，请稍后重试");
            Map<String, Object> payload = new java.util.LinkedHashMap<>(body);
            payload.remove("cache");
            payload.remove("status");
            payload.remove("message");
            return mapper.convertValue(payload, DeviceStateReadResult.class);
        });
    }

    private Map<String, Object> companyDevices(RequestIdentity identity) {
        AuthorizedDeviceScope value = source.loadAuthorizedDeviceScope(identity).block(LOAD_TIMEOUT);
        if (value == null || !identity.equals(value.identity()) || value.source() != identity.source()) {
            throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "当前设备目录不可用", 503);
        }
        return Map.of("source", identity.source().wireValue(), "devices",
                mapper.convertValue(value.devices(), new TypeReference<List<Map<String, Object>>>() { }));
    }

    private Map<String, Object> officeRegions(RequestIdentity identity) {
        List<OfficeRegion> value = source.loadOfficeRegions(identity).block(LOAD_TIMEOUT);
        if (value == null) throw new BusinessException("REGION_UNAVAILABLE", "当前区域目录不可用", 503);
        return Map.of("regions", mapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() { }));
    }

    private Map<String, Object> deviceStates(RequestIdentity identity, List<String> deviceIds) {
        DeviceStateReadResult value = source.readDeviceStates(identity, deviceIds).block(LOAD_TIMEOUT);
        if (value == null) throw new BusinessException("DEVICE_STATE_UNAVAILABLE", "设备状态不可用", 503);
        return mapper.convertValue(value, new TypeReference<Map<String, Object>>() { });
    }

    private AuthorizedDeviceScope scope(RequestIdentity identity, Map<String, Object> body) {
        Object raw = body.get("devices");
        if (!(raw instanceof List<?> rows)) throw new BusinessException(
                "IOT_SCOPE_UNAVAILABLE", "当前设备目录格式无效", 503);
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) throw new BusinessException(
                    "IOT_SCOPE_UNAVAILABLE", "当前设备目录格式无效", 503);
            String id = text(row, "id");
            String kind = text(row, "kind");
            String name = text(row, "name");
            if (id == null || kind == null || name == null) throw new BusinessException(
                    "IOT_SCOPE_UNAVAILABLE", "当前设备目录格式无效", 503);
            String address = text(row, "installAddress");
            if (address == null) address = text(row, "location");
            devices.add(new AuthorizedDevice(id, kind, name, address,
                    Boolean.TRUE.equals(row.get("observationOnly"))));
        }
        return AuthorizedDeviceScope.of(identity, identity.source(), devices);
    }

    private Map<String, Object> candidateBody(RequestIdentity identity,
            ltd.qianc.iotagent.iot.DeviceCandidateQuery query,
            ltd.qianc.iotagent.iot.DeviceCandidateBinding binding) {
        ltd.qianc.iotagent.iot.DeviceCandidateSet value = binding == null
                ? source.findDeviceCandidates(identity, query).block(LOAD_TIMEOUT)
                : source.findDeviceCandidates(identity, query, binding).block(LOAD_TIMEOUT);
        if (value == null) throw new BusinessException("IOT_CANDIDATE_UNAVAILABLE", "设备候选范围不可用", 503);
        return value.publicView();
    }

    private ltd.qianc.iotagent.iot.DeviceCandidateSet candidateSet(
            Map<String, Object> body, ltd.qianc.iotagent.iot.DeviceCandidateQuery query) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(body);
        payload.remove("cache");
        payload.remove("status");
        payload.remove("message");
        Object raw = payload.get("devices");
        if (raw instanceof List<?> rows) {
            List<Map<String, Object>> devices = new ArrayList<>();
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row)) throw new BusinessException(
                        "IOT_CANDIDATE_UNAVAILABLE", "设备候选范围格式无效", 503);
                Map<String, Object> device = new java.util.LinkedHashMap<>();
                row.forEach((key, item) -> device.put(String.valueOf(key), item));
                if (!device.containsKey("installAddress")) device.put("installAddress", device.get("location"));
                device.remove("location");
                devices.add(device);
            }
            payload.put("devices", devices);
        }
        payload.putIfAbsent("query", query);
        return mapper.convertValue(payload, ltd.qianc.iotagent.iot.DeviceCandidateSet.class);
    }

    private static void requireReady(Map<String, Object> body, String code, String message) {
        Map<?, ?> cache = body.get("cache") instanceof Map<?, ?> value ? value : Map.of();
        String status = String.valueOf(cache.get("status"));
        if (!"fresh".equals(status) && !"stale".equals(status)) {
            throw new BusinessException(code, message, 503);
        }
    }

    private static String text(Map<?, ?> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static ReadSnapshots.Identity snapshotIdentity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(),
                identity.source().name(), identity.ownerKey());
    }

    @Override public Mono<String> readCreatorDisplayName(RequestIdentity identity) {
        return source.readCreatorDisplayName(identity);
    }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity identity) {
        return source.loadAuthorizedSafetyScope(identity);
    }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedVideoScope(RequestIdentity identity) {
        return source.loadAuthorizedVideoScope(identity);
    }
    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
        return source.loadAuthorizedEnergyScope(identity);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
            ltd.qianc.iotagent.iot.DeviceCandidateQuery query) {
        return findDeviceCandidates(identity, query, null);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
            ltd.qianc.iotagent.iot.DeviceCandidateQuery query, ltd.qianc.iotagent.iot.DeviceCandidateBinding binding) {
        return Mono.fromCallable(() -> {
            Map<String, Object> key = mapper.convertValue(query, new TypeReference<Map<String, Object>>() { });
            if (binding != null) key.put("conversationId", binding.conversationId());
            Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "DEVICE_CANDIDATES", key,
                    ReadSnapshots.PolicyEnum.CURRENT, () -> candidateBody(identity, query, binding));
            requireReady(body, "DEVICE_CANDIDATE_PREPARING", "设备候选范围准备中，请稍后重试");
            return candidateSet(body, query);
        });
    }
    @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateRecord> resolveDeviceCandidateSet(RequestIdentity identity,
            String candidateSetId, String conversationId) {
        return source.resolveDeviceCandidateSet(identity, candidateSetId, conversationId);
    }
    @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity,
            ltd.qianc.iotagent.iot.DeviceCapabilityRequest request, String conversationId) {
        return Mono.fromCallable(() -> {
            List<String> ids = new ArrayList<>();
            for (var selection : request.candidateSets()) {
                var record = source.resolveDeviceCandidateSet(identity, selection.candidateSetId(), conversationId)
                        .block(LOAD_TIMEOUT);
                if (record == null || !identity.tenantCode().equals(record.tenantCode())
                        || !identity.ownerKey().equals(record.ownerKey()) || identity.source() != record.source()
                        || !conversationId.equals(record.conversationId())) {
                    throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集无效", 400);
                }
                for (String id : selection.deviceIds()) {
                    if (record.members().stream().noneMatch(device -> device.id().equals(id))) {
                        throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备不属于所引用的候选集", 400);
                    }
                    ids.add(id);
                }
            }
            return ids;
        }).flatMap(ids -> readDeviceStates(identity, ids));
    }
    @Override public Mono<DeviceStateReadResult> readObservationStates(RequestIdentity identity,
            List<AuthorizedDevice> devices) {
        return source.readObservationStates(identity, devices);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, ltd.qianc.iotagent.iot.DeviceCapabilityRequest request) {
        return source.inspectDeviceCapabilities(identity, request);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, ltd.qianc.iotagent.iot.DeviceCapabilityRequest request, String conversationId) {
        return source.inspectDeviceCapabilities(identity, request, conversationId);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.VideoStream> openVideoStream(RequestIdentity identity, String deviceId) {
        return source.openVideoStream(identity, deviceId);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.VideoStream> openVideoPlayback(RequestIdentity identity,
            String deviceId, ltd.qianc.iotagent.iot.VideoPlaybackWindow window) {
        return source.openVideoPlayback(identity, deviceId, window);
    }
    @Override public Mono<ltd.qianc.iotagent.iot.VideoPlaybackPreparation> prepareVideoPlayback(RequestIdentity identity,
            String deviceId, ltd.qianc.iotagent.iot.VideoPlaybackWindow window) {
        return source.prepareVideoPlayback(identity, deviceId, window);
    }
}
