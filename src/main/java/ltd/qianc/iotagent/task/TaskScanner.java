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
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
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
    private final TaskReportCheckpointComposer reportComposer;
    private final TaskReportRetryStore reportRetries;
    private final TaskExecutionIdentityStore executionIdentities;
    private final Clock clock;
    private volatile boolean stopping;

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, Clock clock) {
        this(phases, tasks, gateway, notifications, (checkpoint, identity) -> java.util.Optional.empty(), null, null, null, clock);
    }

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, ReportFactProducer reports, Clock clock) {
        this(phases, tasks, gateway, notifications, reports, null, null, null, clock);
    }

    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, ReportFactProducer reports,
                       TaskReportCheckpointComposer reportComposer, TaskReportRetryStore reportRetries,
                       TaskExecutionIdentityStore executionIdentities, Clock clock) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.reportComposer = reportComposer;
        this.reportRetries = reportRetries;
        this.executionIdentities = executionIdentities;
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
                        new TaskExecutionGateway.TaskExecutionRequest(phase.taskId(), phase.phaseId(), phase.source(),
                                phase.payloadJson(), phase.dueAt(), task.payloadJson()));
                boolean finished = phases.finish(phase.phaseId(), phase.version() + 1, result.outcome(), result.payloadJson(), clock.instant());
                if (finished) {
                    completed++;
                    if (reportComposer == null) terminalReport(task, phase, result);
                    if (phases.allTerminal(phase.taskId())) {
                        finalizeTerminal(phase, task, result.outcome(), result.payloadJson());
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
                    if (reportComposer == null) terminalReport(task, phase, new TaskExecutionGateway.TaskExecutionResult(status, payload, dispatchStarted));
                    if (phases.allTerminal(phase.taskId())) {
                        finalizeTerminal(phase, task, status, payload);
                    }
                }
            }
        }
        retryReports();
        return new ScanResult(attempted, claimed, completed, interrupted);
    }

    private void finalizeTerminal(TaskPhaseStore.TaskPhase phase, TaskFact task, String fallbackOutcome, String resultPayload) {
        String outcome = aggregateOutcome(phases.outcomes(task.taskId()), fallbackOutcome);
        String reportError = produceTaskReport(task, outcome);
        String notificationPayload = resultPayload;
        if (reportError != null) {
            outcome = "PARTIAL";
            notificationPayload = "{\"outcome\":\"PARTIAL\",\"reportError\":" + quote(reportError) + "}";
        }
        TaskFact terminalTask = completeTask(task.taskId(), fallbackOutcome, outcome);
        if (terminalTask == null) return;
        terminalNotification(terminalTask, phase, new TaskExecutionGateway.TaskExecutionResult(
                terminalTask.status(), notificationPayload, true));
    }

    private void retryReports() {
        if (reportComposer == null || reportRetries == null) return;
        List<TaskFact> pending;
        try { pending = reportRetries.pending(4); }
        catch (RuntimeException unavailable) { return; }
        for (TaskFact task : pending) {
            try {
                if (!phases.allTerminal(task.taskId())) continue;
                String error = produceTaskReport(task, task.status());
                if (error != null) continue;
                String recipient = field(task.payloadJson(), "ownerKey", "");
                String recoveredStatus = task.status();
                try {
                    String aggregate = aggregateOutcome(phases.outcomes(task.taskId()), task.status());
                    if ("PARTIAL".equals(task.status()) && !aggregate.equals(task.status())) {
                        var updated = tasks.updateIfVersion(new TaskFact(task.taskId(), task.version() + 1,
                                aggregate, task.payloadJson(), task.idempotencyKey()), task.version());
                        if (updated.isPresent()) recoveredStatus = updated.get().status();
                    }
                } catch (RuntimeException ignored) {
                    recoveredStatus = task.status();
                }
                if (!recipient.isBlank()) {
                    try {
                        notifications.refresh(task.taskId(), recipient, recoveredStatus,
                                "{\"outcome\":" + quote(recoveredStatus) + ",\"reportRecovered\":true}", clock.instant());
                    } catch (RuntimeException noNotification) {
                        // The report fact itself is durable; a missing inbox row is not a dispatch failure.
                    }
                }
            } catch (RuntimeException ignored) {
                // Bounded retry state is recorded below; this loop must not stop phase scanning.
                try { reportRetries.failed(task.taskId(), "report retry failed"); } catch (RuntimeException ignoredAgain) { }
            }
        }
    }

    private String produceTaskReport(TaskFact task, String outcome) {
        if (reports == null || reportComposer == null) return null;
        List<TaskPhaseStore.PhaseResult> results;
        try { results = phases.terminalPhases(task.taskId()); }
        catch (RuntimeException unavailable) { return reportFailure(task, "任务阶段结果暂不可读"); }
        String payload;
        try { payload = reportComposer.compose(task, results, outcome, clock.instant()); }
        catch (RuntimeException invalid) {
            return reportFailure(task, invalid.getMessage() == null || invalid.getMessage().isBlank()
                    ? "任务检查点证据无效" : invalid.getMessage());
        }
        if (payload == null) return null;
        String phaseId = results.isEmpty() ? task.taskId() : results.get(results.size() - 1).phaseId();
        Instant checkpointAt = results.isEmpty() ? clock.instant() : results.get(results.size() - 1).dueAt();
        String tenant = field(task.payloadJson(), "tenantCode", "");
        String owner = field(task.payloadJson(), "ownerKey", "");
        String source = field(task.payloadJson(), "source", "office");
        String objective = field(task.payloadJson(), "objective", field(task.payloadJson(), "title", ""));
        if (executionIdentities == null) return reportFailure(task, "任务报表执行身份存储未配置");
        RequestIdentity identity;
        try {
            var stored = executionIdentities.loadForTask(task.taskId()).orElse(null);
            if (stored == null) return reportFailure(task, "任务报表执行身份缺失");
            if (!tenant.equals(stored.tenantCode()) || !owner.equals(stored.ownerKey())) {
                return reportFailure(task, "任务报表执行身份与任务归属不一致");
            }
            identity = new RequestIdentity(stored.authorization(), stored.tenantCode(),
                    IotSource.parse(source), stored.ownerKey());
        } catch (RuntimeException unavailable) {
            return reportFailure(task, "任务报表执行身份不可用");
        }
        try {
            java.util.Optional<ltd.qianc.iotagent.report.ReportFact> report = reports.produce(
                    new ReportFactProducer.ReportCheckpoint(task.taskId(), phaseId, source, tenant, owner,
                            objective, outcome, checkpointAt, clock.instant(), payload), identity);
            if (report == null || report.isEmpty()) return reportFailure(task, "报表生成未返回持久事实");
            if (reportRetries != null) {
                try { reportRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
            }
            return null;
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "报表生成失败" : failure.getMessage();
            return reportFailure(task, message);
        }
    }

    private String reportFailure(TaskFact task, String message) {
        if (reportRetries != null) {
            try { reportRetries.failed(task.taskId(), message); }
            catch (RuntimeException persistenceFailure) {
                return message + "；报表失败状态未能持久化: " + persistenceFailure.getMessage();
            }
        }
        return message;
    }

    private static String quote(String value) {
        return MAPPER.getNodeFactory().textNode(value == null ? "" : value).toString();
    }

    public int recover() {
        List<TaskPhaseStore.TaskPhase> recovered = phases.recoverInterrupted(clock.instant());
        for (TaskPhaseStore.TaskPhase phase : recovered) {
            if (!phases.allTerminal(phase.taskId())) continue;
            TaskFact task = tasks.find(phase.taskId()).orElse(null);
            if (task == null || isTerminal(task.status())) continue;
            finalizeTerminal(phase, task, "INTERRUPTED", "{\"outcome\":\"unknown\",\"reason\":\"service_restart\"}");
        }
        retryReports();
        return recovered.size();
    }
    public ScanResult scan() { return scanOnce(); }

    public int recoverInterrupted() { return recover(); }
    public void stopAndDrain() { stopping = true; }
    private void terminalReport(TaskFact task, TaskPhaseStore.TaskPhase phase,
                                TaskExecutionGateway.TaskExecutionResult result) {
        if (!isTerminal(result.outcome())) return;
        try {
            String tenant = field(task.payloadJson(), "tenantCode", "");
            String owner = field(task.payloadJson(), "ownerKey", "");
            if (tenant.isBlank() || owner.isBlank()) return;
            reports.produce(new ReportFactProducer.ReportCheckpoint(
                    phase.taskId(), phase.phaseId(), field(task.payloadJson(), "source", phase.source()),
                    tenant, owner,
                    taskObjective(task.payloadJson()),
                    result.outcome(), phase.dueAt(), clock.instant(), result.payloadJson()));
        } catch (RuntimeException ignored) {
            // Report persistence is best effort after terminal facts are durable;
            // never redispatch a completed checkpoint because artifact storage is unavailable.
        }
    }

    private TaskFact completeTask(String taskId, String fallbackOutcome) {
        return completeTask(taskId, fallbackOutcome, null);
    }

    private TaskFact completeTask(String taskId, String fallbackOutcome, String forcedOutcome) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (!phases.allTerminal(taskId)) return tasks.find(taskId).orElse(null);
            TaskFact current = tasks.find(taskId).orElse(null);
            if (current == null || isTerminal(current.status())) return current;
            String status = forcedOutcome != null ? forcedOutcome
                    : aggregateOutcome(phases.outcomes(taskId), fallbackOutcome);
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
        String tenant = field(payload, "tenantCode", "");
        String recipient = field(payload, "ownerKey", "");
        String source = field(payload, "source", phase.source());
        if (tenant.isBlank() || recipient.isBlank()) return;
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
