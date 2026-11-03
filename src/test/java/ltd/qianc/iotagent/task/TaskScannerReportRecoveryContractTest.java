package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactProducer;
import org.junit.jupiter.api.Test;

class TaskScannerReportRecoveryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void failedAttemptRecordsBackoffAndDoesNotStarveLaterReport() {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-failed", "PARTIAL"));
        tasks.put(task("task-recovered", "PARTIAL"));
        MemoryPhases phases = new MemoryPhases();
        phases.outcomes.put("task-failed", List.of("FAILED"));
        phases.outcomes.put("task-recovered", List.of("FAILED"));
        MemoryRetry retry = new MemoryRetry(tasks.all());
        MemoryNotifications notifications = new MemoryNotifications();
        notifications.record(notification("task-recovered", "PARTIAL"));
        MemoryIdentities identities = new MemoryIdentities(Map.of(
                "task-failed", identity("task-failed"),
                "task-recovered", identity("task-recovered")));
        List<String> produced = new ArrayList<>();
        ReportFactProducer producer = (checkpoint, identity) -> {
            produced.add(checkpoint.taskId());
            if ("task-failed".equals(checkpoint.taskId())) throw new IllegalStateException("evidence unavailable");
            return Optional.of(new ReportFact("report-" + checkpoint.taskId(), "company",
                    "{\"id\":\"report-" + checkpoint.taskId() + "\",\"taskId\":\"" + checkpoint.taskId() + "\"}"));
        };
        TaskScanner scanner = scanner(tasks, phases, notifications, retry, identities, producer);

        scanner.scanOnce();

        assertThat(retry.failed).containsExactly("task-failed");
        assertThat(retry.succeeded).containsExactly("task-recovered");
        assertThat(retry.attempts("task-failed")).isEqualTo(1);
        assertThat(produced).containsExactly("task-failed", "task-recovered");
        assertThat(tasks.find("task-failed").orElseThrow().status()).isEqualTo("PARTIAL");
        assertThat(tasks.find("task-recovered").orElseThrow().status()).isEqualTo("FAILED");
        assertThat(notifications.fact("task-recovered").outcome()).isEqualTo("FAILED");
        assertThat(notifications.fact("task-recovered").payloadJson()).contains("\"reportRecovered\":true");
    }

    @Test
    void missingExecutionIdentityIsRecordedBeforeAnyReportEvidenceAttempt() {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-no-identity", "PARTIAL"));
        MemoryPhases phases = new MemoryPhases();
        phases.outcomes.put("task-no-identity", List.of("FAILED"));
        MemoryRetry retry = new MemoryRetry(tasks.all());
        MemoryNotifications notifications = new MemoryNotifications();
        List<String> produced = new ArrayList<>();
        ReportFactProducer producer = (checkpoint, identity) -> {
            produced.add(checkpoint.taskId());
            return Optional.of(new ReportFact("report", "company", "{}"));
        };
        TaskScanner scanner = scanner(tasks, phases, notifications, retry,
                new MemoryIdentities(Map.of()), producer);

        scanner.scanOnce();

        assertThat(retry.failed).containsExactly("task-no-identity");
        assertThat(retry.attempts("task-no-identity")).isEqualTo(1);
        assertThat(produced).isEmpty();
        assertThat(tasks.find("task-no-identity").orElseThrow().status()).isEqualTo("PARTIAL");
    }

    private static TaskScanner scanner(MemoryTasks tasks, MemoryPhases phases, MemoryNotifications notifications,
                                       MemoryRetry retry, MemoryIdentities identities, ReportFactProducer producer) {
        TaskReportCheckpointComposer composer = (task, results, outcome, generatedAt) ->
                "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}}";
        TaskExecutionGateway gateway = request -> {
            throw new AssertionError("report retry must not dispatch tasks");
        };
        return new TaskScanner(phases, tasks, gateway, notifications, producer, composer, retry, identities, CLOCK);
    }

    private static TaskFact task(String taskId, String status) {
        return new TaskFact(taskId, 1, status,
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\",\"reportRequest\":{}}",
                taskId);
    }

    private static TaskExecutionIdentityStore.TaskExecutionIdentity identity(String taskId) {
        return new TaskExecutionIdentityStore.TaskExecutionIdentity("TENANT", "owner-1", "Bearer " + taskId);
    }

    private static TaskNotificationFact notification(String taskId, String outcome) {
        return new TaskNotificationFact("notice-" + taskId, taskId, "TENANT", "owner-1", "company",
                outcome, NOW, null, "{\"outcome\":\"PARTIAL\",\"reportError\":\"old\"}");
    }

    private static final class MemoryTasks implements TaskFactStore {
        private final Map<String, TaskFact> values = new LinkedHashMap<>();
        private void put(TaskFact task) { values.put(task.taskId(), task); }
        private List<TaskFact> all() { return List.copyOf(values.values()); }
        @Override public TaskFact save(TaskFact task) { values.put(task.taskId(), task); return task; }
        @Override public Optional<TaskFact> find(String taskId) { return Optional.ofNullable(values.get(taskId)); }
        @Override public Optional<TaskFact> findByIdempotencyKey(String key) { return Optional.empty(); }
        @Override public Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
            TaskFact current = values.get(replacement.taskId());
            if (current == null || current.version() != expectedVersion) return Optional.empty();
            values.put(replacement.taskId(), replacement);
            return Optional.of(replacement);
        }
    }

    private static final class MemoryPhases implements TaskPhaseStore {
        private final Map<String, List<String>> outcomes = new LinkedHashMap<>();
        @Override public List<TaskPhase> due(Instant now, int limit) { return List.of(); }
        @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) { return false; }
        @Override public Optional<TaskPhase> find(String phaseId) { return Optional.empty(); }
        @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) { return false; }
        @Override public int recoverExecuting(Instant at) { return 0; }
        @Override public boolean allTerminal(String taskId) { return true; }
        @Override public List<String> outcomes(String taskId) { return outcomes.getOrDefault(taskId, List.of("FAILED")); }
    }

    private static final class MemoryNotifications implements TaskNotificationStore {
        private final Map<String, TaskNotificationFact> byTask = new LinkedHashMap<>();
        @Override public TaskNotificationFact record(TaskNotificationFact notification) {
            byTask.putIfAbsent(notification.taskId(), notification);
            return byTask.get(notification.taskId());
        }
        @Override public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before) {
            return List.copyOf(byTask.values());
        }
        @Override public TaskNotificationFact refresh(String taskId, String recipientKey, String outcome, String payloadJson, Instant at) {
            TaskNotificationFact current = byTask.get(taskId);
            if (current == null) throw new IllegalStateException("notification missing");
            TaskNotificationFact updated = new TaskNotificationFact(current.notificationId(), current.taskId(),
                    current.tenantCode(), current.recipientKey(), current.source(), outcome, current.endedAt(),
                    current.readAt(), payloadJson);
            byTask.put(taskId, updated);
            return updated;
        }
        private TaskNotificationFact fact(String taskId) { return byTask.get(taskId); }
    }

    private static final class MemoryRetry implements TaskReportRetryStore {
        private final List<TaskFact> tasks;
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final List<String> failed = new ArrayList<>();
        private final List<String> succeeded = new ArrayList<>();
        private MemoryRetry(List<TaskFact> tasks) { this.tasks = tasks; }
        @Override public List<TaskFact> pending(int limit) { return tasks; }
        @Override public void failed(String taskId, String error) {
            failed.add(taskId);
            attempts.merge(taskId, 1, Integer::sum);
        }
        @Override public void succeeded(String taskId) { succeeded.add(taskId); }
        private int attempts(String taskId) { return attempts.getOrDefault(taskId, 0); }
    }

    private static final class MemoryIdentities implements TaskExecutionIdentityStore {
        private final Map<String, TaskExecutionIdentityStore.TaskExecutionIdentity> values;
        private MemoryIdentities(Map<String, TaskExecutionIdentityStore.TaskExecutionIdentity> values) { this.values = values; }
        @Override public void saveForTask(String taskId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) { values.put(taskId, identity); }
        @Override public void saveForPlan(String planId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) { }
        @Override public Optional<TaskExecutionIdentityStore.TaskExecutionIdentity> loadForTask(String taskId) { return Optional.ofNullable(values.get(taskId)); }
    }
}
