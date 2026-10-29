package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.report.ReportFactProducer;

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
    private final ReportFactProducer reports;
    private final Clock clock;
    private volatile boolean stopping;

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, Clock clock) {
        this(phases, tasks, gateway, notifications, checkpoint -> java.util.Optional.empty(), clock);
    }

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, ReportFactProducer reports, Clock clock) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScanResult scanOnce() {
        if (stopping) return new ScanResult(0, 0, 0, 0);
        Instant now = clock.instant();
        int attempted = 0, claimed = 0, completed = 0, interrupted = 0;
        List<TaskPhaseStore.TaskPhase> due = phases.due(now, 4);
        for (TaskPhaseStore.TaskPhase phase : due) {
            attempted++;
            if (!phases.claim(phase.phaseId(), phase.version(), now)) continue;
            claimed++;
            TaskFact task = tasks.find(phase.taskId()).orElse(null);
            if (task == null || !dispatchable(task.status())) {
                phases.finish(phase.phaseId(), phase.version() + 1, "CANCELLED",
                        "{\"outcome\":\"cancelled\",\"reason\":\"task_not_dispatchable\"}", clock.instant());
                continue;
            }
            try {
                TaskExecutionGateway.TaskExecutionResult result = gateway.execute(
                        new TaskExecutionGateway.TaskExecutionRequest(phase.taskId(), phase.phaseId(), phase.source(), phase.payloadJson(), phase.dueAt()));
                boolean finished = phases.finish(phase.phaseId(), phase.version() + 1, result.outcome(), result.payloadJson(), clock.instant());
                if (finished) {
                    completed++;
                    terminalReport(task, phase, result);
                    if (phases.allTerminal(phase.taskId())) {
                        TaskFact terminalTask = completeTask(phase.taskId(), result.outcome());
                        if (terminalTask != null) {
                            TaskExecutionGateway.TaskExecutionResult terminalResult =
                                    new TaskExecutionGateway.TaskExecutionResult(terminalTask.status(), result.payloadJson(), result.dispatchStarted());
                            terminalNotification(terminalTask, phase, terminalResult);
                        }
                    }
                }
            } catch (RuntimeException error) {
                // Dispatch may already have crossed the gateway boundary. Preserve unknown/interrupted;
                // do not roll back to pending and do not allow a second execution after restart.
                boolean dispatchStarted = !(error instanceof TaskExecutionGateway.DispatchException dispatch)
                        || dispatch.dispatchStarted();
                String status = dispatchStarted ? "INTERRUPTED" : "FAILED";
                String payload = dispatchStarted
                        ? "{\"outcome\":\"unknown\"}"
                        : "{\"outcome\":\"failed\",\"reason\":\"not_dispatched\"}";
                if (phases.finish(phase.phaseId(), phase.version() + 1, status, payload, clock.instant())) {
                    if (dispatchStarted) interrupted++;
                    terminalReport(task, phase, new TaskExecutionGateway.TaskExecutionResult(status, payload, dispatchStarted));
                    if (phases.allTerminal(phase.taskId())) {
                        TaskFact terminalTask = completeTask(phase.taskId(), status);
                        if (terminalTask != null) {
                            TaskExecutionGateway.TaskExecutionResult terminalResult =
                                    new TaskExecutionGateway.TaskExecutionResult(terminalTask.status(), payload, dispatchStarted);
                            terminalNotification(terminalTask, phase, terminalResult);
                        }
                    }
                }
            }
        }
        return new ScanResult(attempted, claimed, completed, interrupted);
    }

    public int recover() {
        List<TaskPhaseStore.TaskPhase> recovered = phases.recoverInterrupted(clock.instant());
        for (TaskPhaseStore.TaskPhase phase : recovered) {
            if (!phases.allTerminal(phase.taskId())) continue;
            TaskFact task = completeTask(phase.taskId(), "INTERRUPTED");
            if (task != null) {
                TaskExecutionGateway.TaskExecutionResult terminalResult =
                        new TaskExecutionGateway.TaskExecutionResult(task.status(),
                                "{\"outcome\":\"unknown\",\"reason\":\"service_restart\"}", true);
                terminalNotification(task, phase, terminalResult);
                terminalReport(task, phase, terminalResult);
            }
        }
        return recovered.size();
    }
    public ScanResult scan() { return scanOnce(); }

    public int recoverInterrupted() { return recover(); }
    public void stopAndDrain() { stopping = true; }
    private void terminalReport(TaskFact task, TaskPhaseStore.TaskPhase phase,
                                TaskExecutionGateway.TaskExecutionResult result) {
        if (!isTerminal(result.outcome())) return;
        try {
            reports.produce(new ReportFactProducer.ReportCheckpoint(
                    phase.taskId(), phase.phaseId(), field(task.payloadJson(), "source", phase.source()),
                    field(task.payloadJson(), "tenantCode", "unknown"),
                    field(task.payloadJson(), "ownerKey", "unknown"),
                    taskObjective(task.payloadJson()),
                    result.outcome(), phase.dueAt(), clock.instant(), result.payloadJson()));
        } catch (RuntimeException ignored) {
            // Report persistence is best effort after terminal facts are durable;
            // never redispatch a completed checkpoint because artifact storage is unavailable.
        }
    }

    private TaskFact completeTask(String taskId, String fallbackOutcome) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (!phases.allTerminal(taskId)) return tasks.find(taskId).orElse(null);
            TaskFact current = tasks.find(taskId).orElse(null);
            if (current == null || isTerminal(current.status())) return current;
            String status = aggregateOutcome(phases.outcomes(taskId), fallbackOutcome);
            Optional<TaskFact> update = tasks.updateIfVersion(new TaskFact(
                    current.taskId(), current.version() + 1, status, current.payloadJson(), current.idempotencyKey()), current.version());
            if (update.isPresent()) return update.get();
        }
        return tasks.find(taskId).orElse(null);
    }

    private static boolean dispatchable(String status) {
        return "SCHEDULED".equals(status);
    }

    private static String aggregateOutcome(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) return normalizeOutcome(fallback);
        boolean allCompleted = true;
        boolean allCompletedOrUnknown = true;
        boolean hasUsable = false;
        for (String value : values) {
            String outcome = normalizeOutcome(value);
            allCompleted &= "COMPLETED".equals(outcome);
            allCompletedOrUnknown &= "COMPLETED".equals(outcome) || "UNCONFIRMED".equals(outcome)
                    || "INTERRUPTED".equals(outcome);
            hasUsable |= "COMPLETED".equals(outcome) || "PARTIAL".equals(outcome)
                    || "UNCONFIRMED".equals(outcome) || "INTERRUPTED".equals(outcome);
        }
        if (allCompleted) return "COMPLETED";
        if (allCompletedOrUnknown) return "UNCONFIRMED";
        return hasUsable ? "PARTIAL" : "FAILED";
    }

    private static String normalizeOutcome(String value) {
        return value == null ? "FAILED" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void terminalNotification(TaskPhaseStore.TaskPhase phase, TaskExecutionGateway.TaskExecutionResult result) {
        TaskFact task = tasks.find(phase.taskId()).orElse(null);
        if (task != null) terminalNotification(task, phase, result);
    }

    private void terminalNotification(TaskFact task, TaskPhaseStore.TaskPhase phase,
                                      TaskExecutionGateway.TaskExecutionResult result) {
        if (!isTerminal(result.outcome())) return;
        var payload = task.payloadJson();
        String tenant = field(payload, "tenantCode", "unknown");
        String recipient = field(payload, "ownerKey", "unknown");
        String source = field(payload, "source", phase.source());
        try {
            notifications.record(new TaskNotificationFact(
                    notificationId(phase.taskId(), recipient), phase.taskId(), tenant, recipient, source,
                    result.outcome(), clock.instant(), null, result.payloadJson()));
        } catch (RuntimeException ignored) {
            // Notification persistence is retried from the terminal fact, never by redispatching the phase.
        }
    }
    private static String notificationId(String taskId, String recipient) {
        return UUID.nameUUIDFromBytes((taskId + "\u0000" + recipient).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean isTerminal(String value) {
        return "COMPLETED".equals(value) || "PARTIAL".equals(value) || "FAILED".equals(value)
                || "UNCONFIRMED".equals(value) || "INTERRUPTED".equals(value) || "CANCELLED".equals(value);
    }
    private static String taskObjective(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String direct = root.path("objective").asText("");
            if (!direct.isBlank()) return direct;
            JsonNode phases = root.path("phases");
            if (phases.isArray()) {
                for (JsonNode phase : phases) {
                    String phaseObjective = phase.path("read").path("agent").path("objective").asText("");
                    if (!phaseObjective.isBlank()) return phaseObjective;
                    phaseObjective = phase.path("objective").asText("");
                    if (!phaseObjective.isBlank()) return phaseObjective;
                }
            }
        } catch (Exception ignored) {
            // Malformed task metadata is already handled fail-closed by the
            // authorization fields; it must not prevent terminal persistence.
        }
        return "";
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
