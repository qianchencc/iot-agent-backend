package ltd.qianc.iotagent.task.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.task.TaskReadScope;

/** Read projections use the local directory; mutations retain live binding verification. */
public final class TaskReadCatalog {
    private final IotAdapter iot;
    private final ReadSnapshots snapshots;

    public TaskReadCatalog(IotAdapter iot, ReadSnapshots snapshots) {
        this.iot = Objects.requireNonNull(iot);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    public TaskReadScope read(RequestIdentity identity) {
        try {
            var batch = iot.readDirectory(identity).block(Duration.ofSeconds(30));
            if (batch == null || !identity.equals(batch.scope().identity())) throw unavailable();
            TaskReadScope scope = TaskReadScope.of(batch.scope());
            if (identity.source() != ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE) return scope;
            Set<String> regions = new LinkedHashSet<>();
            for (var region : batch.regions()) regions.add(region.id());
            return new Catalog(scope, Collections.unmodifiableSet(regions));
        } catch (BusinessException failure) {
            if ("IOT_SCOPE_PREPARING".equals(failure.code())) {
                throw new BusinessException("TASK_READ_PREPARING", "任务授权范围准备中，请稍后重试", 503);
            }
            if (failure.httpStatus() == 401 || failure.httpStatus() == 403) throw failure;
            throw unavailable();
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    public TaskReadScope load(RequestIdentity identity) {
        return read(identity);
    }

    /** Mutations still validate the live catalog and control bindings. */
    public AuthorizedDeviceScope fresh(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = iot.revalidateAuthorizedDeviceScope(identity).block(Duration.ofSeconds(30));
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

    private static BusinessException unavailable() {
        return new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "当前设备范围暂不可用", 503);
    }

    private record Catalog(TaskReadScope scope, Set<String> regionIds) implements TaskReadScope {
        @Override public Optional<DeviceView> device(String deviceId) {
            return scope.device(deviceId);
        }
        @Override public Set<String> deviceIds() { return scope.deviceIds(); }
    }
}
