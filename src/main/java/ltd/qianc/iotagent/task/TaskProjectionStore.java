package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;

/** Read-model seam for timeline/calendar projections, always scoped by current identity. */
public interface TaskProjectionStore {
    List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to);
    List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to);
    List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to);
    List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to);
    List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to);
}
