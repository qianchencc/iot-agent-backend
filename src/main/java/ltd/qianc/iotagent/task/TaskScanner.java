package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Recovery-safe scanner. A phase must be claimed by durable CAS before gateway
 * dispatch; executing phases are never retried after a restart.
 */
public final class TaskScanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TaskPhaseStore phases;
    private final TaskFactStore tasks;
    private final TaskExecutionGateway gateway;
    private final TaskNotificationStore notifications;
    private final Clock clock;

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, Clock clock) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScanResult scanOnce() {
        Instant now = clock.instant();
        int attempted = 0, claimed = 0, completed = 0, interrupted = 0;
        List<TaskPhaseStore.TaskPhase> due = phases.due(now, 4);
        for (TaskPhaseStore.TaskPhase phase : due) {
            attempted++;
            if (!phases.claim(phase.phaseId(), phase.version(), now)) continue;
            claimed++;
            try {
                TaskExecutionGateway.TaskExecutionResult result = gateway.execute(
                        new TaskExecutionGateway.TaskExecutionRequest(phase.taskId(), phase.phaseId(), phase.source(), phase.payloadJson(), phase.dueAt()));
                boolean finished = phases.finish(phase.phaseId(), phase.version() + 1, result.outcome(), result.payloadJson(), clock.instant());
                if (finished) {
                    completed++;
                    if (isTerminal(result.outcome())) terminalNotification(phase, result);
                }
            } catch (RuntimeException error) {
                // Dispatch may already have crossed the gateway boundary. Preserve unknown/interrupted;
                // do not roll back to pending and do not allow a second execution after restart.
                if (phases.finish(phase.phaseId(), phase.version() + 1, "INTERRUPTED", "{\"outcome\":\"unknown\"}", clock.instant())) interrupted++;
            }
        }
        return new ScanResult(attempted, claimed, completed, interrupted);
    }

    public int recover() { return phases.recoverExecuting(clock.instant()); }
    public ScanResult scan() { return scanOnce(); }

    public int recoverInterrupted() { return recover(); }

    private void terminalNotification(TaskPhaseStore.TaskPhase phase, TaskExecutionGateway.TaskExecutionResult result) {
        TaskFact task = tasks.find(phase.taskId()).orElse(null);
        if (task == null || !isTerminal(result.outcome())) return;
        var payload = task.payloadJson();
        String tenant = field(payload, "tenantCode", "unknown");
        String recipient = field(payload, "ownerKey", "unknown");
        String source = field(payload, "source", phase.source());
        try {
            notifications.record(new TaskNotificationFact(
                    phase.taskId() + ":" + recipient, phase.taskId(), tenant, recipient, source,
                    result.outcome(), clock.instant(), null, result.payloadJson()));
        } catch (RuntimeException ignored) {
            // Notification persistence is retried from the terminal fact, never by redispatching the phase.
        }
    }
    private static boolean isTerminal(String value) {
        return "COMPLETED".equals(value) || "PARTIAL".equals(value) || "FAILED".equals(value)
                || "UNCONFIRMED".equals(value) || "INTERRUPTED".equals(value) || "CANCELLED".equals(value);
    }
    private static String field(String json, String key, String fallback) {
        try {
            JsonNode value = MAPPER.readTree(json).path(key);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
    public record ScanResult(int attempted, int claimed, int completed, int interrupted) { }
}
