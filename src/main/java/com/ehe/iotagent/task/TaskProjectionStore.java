package com.ehe.iotagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ehe.iotagent.auth.PrincipalInfo;

/** Read-model seam for timeline/calendar projections, always scoped by current identity. */
public interface TaskProjectionStore {
    List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to);
    /** Background composition using verified identity; may prepare authorization inline, unlike list(). */
    default List<Map<String, Object>> listByIdentity(com.ehe.iotagent.iot.RequestIdentity identity, Instant from, Instant to) {
        throw new UnsupportedOperationException("Authenticated task projection is not configured");
    }
    List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to);
    List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to);
    List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to);
    List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to);

    /**
     * Runtime regionAttention for the office workbench: ids of the caller's
     * authorized regions holding an exceptional task of the same tenant that was
     * updated strictly after that region's watermark and still exposes at least
     * one currently authorized phase device. {@code seenByRegion} carries only
     * regions with an existing watermark; an absent region was never acknowledged
     * and therefore counts every exceptional task. Fails closed when the read
     * authorization scope is unavailable.
     */
    Set<String> regionAttention(PrincipalInfo principal, Map<String, Instant> seenByRegion);
}
