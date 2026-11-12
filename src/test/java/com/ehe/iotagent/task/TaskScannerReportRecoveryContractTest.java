package com.ehe.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.ehe.iotagent.report.ReportFact;
import com.ehe.iotagent.report.ReportFactProducer;
import org.junit.jupiter.api.Test;

class TaskScannerReportRecoveryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void failedAttemptRecordsBackoffAndDoesNotStarveLaterReport() {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-failed", "FAILED"));
        tasks.put(task("task-recovered", "FAILED"));
        MemoryPhases phases = new MemoryPhases();
        MemoryRetry retry = new MemoryRetry(tasks.all());
        MemoryNotifications notifications = new MemoryNotifications();
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

        scanner.recoverReports();

        assertThat(retry.failed).containsExactly("task-failed");
        assertThat(retry.succeeded).containsExactly("task-recovered");
        assertThat(retry.attempts("task-failed")).isEqualTo(1);
        assertThat(produced).containsExactly("task-failed", "task-recovered");
        assertThat(tasks.find("task-failed").orElseThrow().status())
                .as("报表重试不得改写执行聚合结果").isEqualTo("FAILED");
        assertThat(tasks.find("task-recovered").orElseThrow().status())
                .as("报表恢复不得改写执行聚合结果").isEqualTo("FAILED");
        assertThat(notifications.byTask).isEmpty();
    }

    @Test
    void missingExecutionIdentityIsRecordedBeforeAnyReportEvidenceAttempt() {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-no-identity", "FAILED"));
        MemoryPhases phases = new MemoryPhases();
        MemoryRetry retry = new MemoryRetry(tasks.all());
        MemoryNotifications notifications = new MemoryNotifications();
        List<String> produced = new ArrayList<>();
        ReportFactProducer producer = (checkpoint, identity) -> {
            produced.add(checkpoint.taskId());
            return Optional.of(new ReportFact("report", "company", "{}"));
        };
        TaskScanner scanner = scanner(tasks, phases, notifications, retry,
                new MemoryIdentities(Map.of()), producer);

        scanner.recoverReports();

        assertThat(retry.failed).containsExactly("task-no-identity");
        assertThat(retry.attempts("task-no-identity")).isEqualTo(1);
        assertThat(produced).isEmpty();
        assertThat(tasks.find("task-no-identity").orElseThrow().status())
                .as("报表缺失身份不得改写执行聚合结果").isEqualTo("FAILED");
    }

    @Test
    void failedReportLeavesExecutionOutcomeCompleted() {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-report-down", "EXECUTING"));
        MemoryPhases phases = new MemoryPhases();
        phases.outcomes.put("task-report-down", List.of("COMPLETED"));
        phases.ready.add("task-report-down");
        MemoryRetry retry = new MemoryRetry(List.of());
        MemoryNotifications notifications = new MemoryNotifications();
        MemoryIdentities identities = new MemoryIdentities(Map.of(
                "task-report-down", identity("task-report-down")));
        ReportFactProducer producer = (checkpoint, identity) -> {
            throw new IllegalStateException("报表存储不可用");
        };
        TaskScanner scanner = scanner(tasks, phases, notifications, retry, identities, producer);

        scanner.scanOnce();
        retry.tasks.add(tasks.find("task-report-down").orElseThrow());
        scanner.recoverReports();

        assertThat(tasks.find("task-report-down").orElseThrow().status())
                .as("报表失败不得把执行结果改写为 PARTIAL").isEqualTo("COMPLETED");
        assertThat(retry.failed).containsExactly("task-report-down");
        assertThat(retry.attempts("task-report-down")).isEqualTo(1);
        assertThat(notifications.byTask.get("task-report-down").outcome())
                .as("终态通知必须报告真实执行聚合结果").isEqualTo("COMPLETED");
    }

    @Test
    void slowReportDoesNotHoldTaskTerminalOrPhaseClock() throws Exception {
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("slow-report", "EXECUTING"));
        MemoryPhases phases = new MemoryPhases();
        phases.outcomes.put("slow-report", List.of("COMPLETED"));
        phases.ready.add("slow-report");
        MemoryRetry retry = new MemoryRetry(List.of());
        MemoryNotifications notifications = new MemoryNotifications();
        MemoryIdentities identities = new MemoryIdentities(Map.of("slow-report", identity("slow-report")));
        CountDownLatch reportStarted = new CountDownLatch(1);
        CountDownLatch releaseReport = new CountDownLatch(1);
        ReportFactProducer producer = (checkpoint, identity) -> {
            reportStarted.countDown();
            try {
                if (!releaseReport.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("report held");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return Optional.empty();
        };
        TaskScanner scanner = scanner(tasks, phases, notifications, retry, identities, producer);
        var scan = CompletableFuture.supplyAsync(scanner::scanOnce);
        CompletableFuture<Void> reporting = null;
        try {
            scan.get(2, TimeUnit.SECONDS);
            assertThat(tasks.find("slow-report").orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(notifications.byTask.get("slow-report").outcome()).isEqualTo("COMPLETED");
            retry.tasks.add(tasks.find("slow-report").orElseThrow());
            reporting = CompletableFuture.runAsync(scanner::recoverReports);
            assertThat(reportStarted.await(2, TimeUnit.SECONDS)).isTrue();
            tasks.put(task("later-terminal", "EXECUTING"));
            phases.outcomes.put("later-terminal", List.of("COMPLETED"));
            phases.ready.add("later-terminal");
            CompletableFuture.supplyAsync(scanner::scanOnce).get(2, TimeUnit.SECONDS);
            assertThat(tasks.find("later-terminal").orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(notifications.byTask.get("later-terminal").outcome()).isEqualTo("COMPLETED");
            assertThat(reporting.isDone()).isFalse();
        } finally {
            releaseReport.countDown();
            scan.get(5, TimeUnit.SECONDS);
            if (reporting != null) reporting.get(5, TimeUnit.SECONDS);
        }
    }

    private static TaskScanner scanner(MemoryTasks tasks, MemoryPhases phases, MemoryNotifications notifications,
                                       MemoryRetry retry, MemoryIdentities identities, ReportFactProducer producer) {
        TaskReportCheckpointComposer composer = (task, results, outcome, generatedAt) ->
                "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}}";
        TaskExecutionGateway gateway = request -> {
            throw new AssertionError("report retry must not dispatch tasks");
        };
        return new TaskScanner(phases, tasks, gateway, notifications, producer, composer, retry,
                null, identities, null, null, null, CLOCK);
    }

    private static TaskFact task(String taskId, String status) {
        return new TaskFact(taskId, 1, status,
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\",\"reportRequest\":{}}",
                taskId);
    }

    private static TaskExecutionIdentityStore.TaskExecutionIdentity identity(String taskId) {
        return new TaskExecutionIdentityStore.TaskExecutionIdentity("TENANT", "owner-1", "Bearer " + taskId);
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
        private final List<String> ready = new ArrayList<>();
        @Override public List<TaskPhase> due(Instant now, int limit) { return List.of(); }
        @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) { return false; }
        @Override public Optional<TaskPhase> find(String phaseId) { return Optional.empty(); }
        @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) { return false; }
        @Override public int recoverExecuting(Instant at) { return 0; }
        @Override public boolean allTerminal(String taskId) { return true; }
        @Override public List<String> outcomes(String taskId) { return outcomes.getOrDefault(taskId, List.of("FAILED")); }
        @Override public List<String> readyToFinalize(Instant now, int limit) { return List.copyOf(ready); }
    }

    private static final class MemoryNotifications implements TaskNotificationStore {
        private final Map<String, TaskNotificationFact> byTask = new LinkedHashMap<>();
        @Override public Optional<TaskNotificationFact> find(String id) {
            return byTask.values().stream().filter(item -> item.notificationId().equals(id)).findFirst();
        }
        @Override public TaskNotificationFact record(TaskNotificationFact notification) {
            byTask.putIfAbsent(notification.taskId(), notification);
            return byTask.get(notification.taskId());
        }
        @Override public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before) {
            return List.copyOf(byTask.values());
        }
    }

    private static final class MemoryRetry implements TaskReportRetryStore {
        private final List<TaskFact> tasks;
        private final Map<String, Integer> attempts = new LinkedHashMap<>();
        private final List<String> failed = new ArrayList<>();
        private final List<String> succeeded = new ArrayList<>();
        private MemoryRetry(List<TaskFact> tasks) { this.tasks = new ArrayList<>(tasks); }
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
