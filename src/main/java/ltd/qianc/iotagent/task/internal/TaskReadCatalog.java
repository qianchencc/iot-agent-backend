package ltd.qianc.iotagent.task.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.task.TaskReadScope;

/** Read-only authorization metadata shares the backend refresh and credential-revocation model. */
public final class TaskReadCatalog {
    private final IotAdapter iot;
    private final ReadSnapshots snapshots;

    public TaskReadCatalog(IotAdapter iot, ReadSnapshots snapshots) {
        this.iot = Objects.requireNonNull(iot);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    /** Never crawls upstream on the HTTP thread; task and notification facts are not cached here. */
    public TaskReadScope read(RequestIdentity identity) {
        Map<String, Object> body = snapshots.read(snapshotIdentity(identity), "TASK_READ_SCOPE_V1", Map.of(),
                ReadSnapshots.PolicyEnum.CURRENT, () -> scopeView(TaskReadScope.of(fresh(identity))));
        if ("preparing".equals(body.get("status"))) {
            throw new BusinessException("TASK_READ_PREPARING", "任务授权范围准备中，请稍后重试", 503);
        }
        // A running renewal can use the last successful scope, but a failed renewal
        // must not keep granting visibility indefinitely. Revocation is checked by snapshots.read.
        if ("unavailable".equals(body.get("status"))
                || body.get("cache") instanceof Map<?, ?> cache && cache.get("lastError") != null) {
            throw unavailable();
        }
        if (!(body.get("devices") instanceof List<?> rows)
                || !(body.get("regionIds") instanceof List<?> regionRows)) throw unavailable();
        Map<String, TaskReadScope.DeviceView> devices = new LinkedHashMap<>();
        Set<String> regions = new LinkedHashSet<>();
        try {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> value)
                        || !(value.get("id") instanceof String id) || id.isBlank()
                        || !(value.get("kind") instanceof String kind)) throw unavailable();
                devices.putIfAbsent(id, new TaskReadScope.DeviceView(id, kind,
                        (String) value.get("name"), (String) value.get("installAddress"),
                        Boolean.TRUE.equals(value.get("observationOnly"))));
            }
            for (Object row : regionRows) {
                if (!(row instanceof String id)) throw unavailable();
                regions.add(id);
            }
        } catch (ClassCastException invalid) {
            throw unavailable();
        }
        return new Catalog(Collections.unmodifiableMap(devices), Collections.unmodifiableSet(regions));
    }

    /** Background snapshot composition may prepare a cold catalog inline, never ordinary HTTP. */
    public TaskReadScope load(RequestIdentity identity) {
        snapshots.load(snapshotIdentity(identity), "TASK_READ_SCOPE_V1", Map.of(),
                ReadSnapshots.PolicyEnum.CURRENT, () -> scopeView(TaskReadScope.of(fresh(identity))));
        return read(identity);
    }

    /** Mutations still validate the live catalog and control bindings. */
    public AuthorizedDeviceScope fresh(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(30));
            if (scope == null || scope.source() != identity.source() || !identity.equals(scope.identity())) {
                throw unavailable();
            }
            return scope;
        } catch (BusinessException failure) {
            if (failure.httpStatus() == 401 || failure.httpStatus() == 403) {
                snapshots.revoke(snapshotIdentity(identity), failure);
            }
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static ReadSnapshots.Identity snapshotIdentity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(),
                identity.source().name(), identity.ownerKey());
    }

    private static Map<String, Object> scopeView(TaskReadScope scope) {
        return Map.of("devices", scope.deviceIds().stream().map(id -> {
            TaskReadScope.DeviceView device = scope.device(id).orElseThrow();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", device.id());
            view.put("kind", device.kind());
            view.put("name", device.name());
            view.put("installAddress", device.installAddress());
            view.put("observationOnly", device.observationOnly());
            return view;
        }).toList(), "regionIds", scope.regionIds());
    }

    private static BusinessException unavailable() {
        return new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "当前设备范围暂不可用", 503);
    }

    private record Catalog(Map<String, TaskReadScope.DeviceView> devices, Set<String> regionIds)
            implements TaskReadScope {
        @Override public Optional<DeviceView> device(String deviceId) {
            return Optional.ofNullable(deviceId == null ? null : devices.get(deviceId));
        }
        @Override public Set<String> deviceIds() { return devices.keySet(); }
    }
}
