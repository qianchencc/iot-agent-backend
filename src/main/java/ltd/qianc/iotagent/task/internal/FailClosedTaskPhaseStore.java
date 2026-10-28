package ltd.qianc.iotagent.task.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskPhaseStore;

public final class FailClosedTaskPhaseStore implements TaskPhaseStore {
    private final String reason;
    public FailClosedTaskPhaseStore(String reason) { this.reason = reason; }
    @Override public List<TaskPhase> due(Instant now, int limit) { throw unavailable(); }
    @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) { throw unavailable(); }
    @Override public Optional<TaskPhase> find(String phaseId) { throw unavailable(); }
    @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) { throw unavailable(); }
    @Override public int recoverExecuting(Instant at) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
