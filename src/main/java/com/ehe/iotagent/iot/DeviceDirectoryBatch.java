package com.ehe.iotagent.iot;

import java.util.List;
import java.util.Objects;

/** One complete authorized collection: empty Office regions remain facts even without devices. */
public record DeviceDirectoryBatch(AuthorizedDeviceScope scope, List<OfficeRegion> regions,
        String synchronizedAt, boolean stale) {
    /** A collected batch has not yet been committed locally. */
    public DeviceDirectoryBatch(AuthorizedDeviceScope scope, List<OfficeRegion> regions) {
        this(scope, regions, null, true);
    }
    public DeviceDirectoryBatch {
        Objects.requireNonNull(scope, "scope");
        regions = List.copyOf(regions);
    }

    public java.util.Map<String, Object> cacheMetadata() {
        var cache = new java.util.LinkedHashMap<String, Object>();
        cache.put("status", stale ? "stale" : "fresh");
        cache.put("updatedAt", synchronizedAt);
        return cache;
    }

    public AuthorizedDeviceScope forRegion(String regionId) {
        if (scope.source() != IotSourceEnum.OFFICE || regionId == null || regionId.isBlank()) return scope;
        if (regions.stream().noneMatch(region -> region.id().equals(regionId))) {
            throw new com.ehe.iotagent.error.BusinessException("FOCUS_REGION_UNAVAILABLE", "当前关注区域不在用户授权范围内", 403);
        }
        return scope.retain(device -> device.officeIds().contains(regionId));
    }
}
