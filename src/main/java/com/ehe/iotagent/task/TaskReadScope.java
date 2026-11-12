package com.ehe.iotagent.task;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;

/**
 * Viewer's authorized read catalog used by every public task projection. It is the
 * Runtime catalog.getDevice()/catalog.regions seam: devices outside this scope are
 * trimmed from details, reports, timelines and calendars instead of being exposed
 * after a tenant-only check.
 */
public interface TaskReadScope {
    Optional<DeviceView> device(String deviceId);

    Set<String> deviceIds();

    Set<String> regionIds();

    /** Device identity fields the public projections need; never carries bindings. */
    record DeviceView(String id, String kind, String name, String installAddress, boolean observationOnly) { }

    /** Resolves the read scope of one principal and agent source, fail-closed upstream. */
    interface Provider {
        TaskReadScope scope(PrincipalInfo principal, String source);

        /** Background composition may prepare a cold catalog; never call this from ordinary HTTP. */
        default TaskReadScope loadScope(com.ehe.iotagent.iot.RequestIdentity identity) {
            return scope(new PrincipalInfo(identity.authorization(), identity.tenantCode()),
                    identity.source().wireValue());
        }
    }

    static TaskReadScope of(AuthorizedDeviceScope scope) {
        Map<String, DeviceView> devices = scope.devices().stream().collect(Collectors.toMap(
                com.ehe.iotagent.iot.AuthorizedDevice::id,
                device -> new DeviceView(device.id(), device.kind(), device.name(),
                        device.installAddress(), device.observationOnly()),
                (left, right) -> left));
        Set<String> regions = scope.devices().stream()
                .flatMap(device -> device.officeIds().stream())
                .collect(Collectors.toUnmodifiableSet());
        Map<String, DeviceView> index = devices;
        return new TaskReadScope() {
            @Override public Optional<DeviceView> device(String deviceId) {
                return Optional.ofNullable(deviceId == null ? null : index.get(deviceId));
            }
            @Override public Set<String> deviceIds() { return index.keySet(); }
            @Override public Set<String> regionIds() { return regions; }
        };
    }

    /** Scope without any authorized device; projections degrade to owner-only rows. */
    static TaskReadScope empty() {
        return new TaskReadScope() {
            @Override public Optional<DeviceView> device(String deviceId) { return Optional.empty(); }
            @Override public Set<String> deviceIds() { return Set.of(); }
            @Override public Set<String> regionIds() { return Set.of(); }
        };
    }

    /** Fails exactly like the Runtime routes when the read catalog cannot be loaded. */
    static TaskReadScope unavailable(String message) {
        throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", message, 503);
    }

    /** Convenience mapper for kind checks used by report-block visibility rules. */
    default Function<String, Optional<String>> kindOf() {
        return deviceId -> device(deviceId).map(DeviceView::kind);
    }
}
