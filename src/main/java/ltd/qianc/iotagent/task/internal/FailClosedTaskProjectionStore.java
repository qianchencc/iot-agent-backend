package ltd.qianc.iotagent.task.internal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.task.TaskProjectionStore;

public final class FailClosedTaskProjectionStore implements TaskProjectionStore {
    private final String reason;
    public FailClosedTaskProjectionStore(String reason) { this.reason = reason; }
    @Override public List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to) { throw unavailable(); }
    @Override public List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) { throw unavailable(); }
    @Override public List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) { throw unavailable(); }
    @Override public List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) { throw unavailable(); }
    @Override public List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
