package ltd.qianc.iotagent.task;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.report.ReportFactProducer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskScannerReportProducerTest {
    private static final Instant NOW = Instant.parse("2026-10-12T01:00:01Z");
    private static final Instant DUE = Instant.parse("2026-10-12T01:00:00Z");

    @Test
    void terminalCheckpointPassesCompletedPayloadToReportProducerWithoutASecondRoute() {
        MemoryPhase phases = new MemoryPhase();
        MemoryTasks tasks = new MemoryTasks(new TaskFact("task-1", 1, "SCHEDULED",
                "{\"source\":\"company\",\"tenantCode\":\"XSJ\",\"ownerKey\":\"owner-1\",\"objective\":\"保存报告\"}", "task-1"));
        List<ReportFactProducer.ReportCheckpoint> received = new ArrayList<>();
        TaskExecutionGateway gateway = request -> new TaskExecutionGateway.TaskExecutionResult(
                "COMPLETED", "{\"report\":{\"title\":\"昨日用电简报\",\"blocks\":[]}}", true);
        TaskNotificationStore notifications = new Notifications();

        TaskScanner scanner = new TaskScanner(phases, tasks, gateway, notifications,
                checkpoint -> { received.add(checkpoint); return Optional.empty(); },
                Clock.fixed(NOW, ZoneOffset.UTC));

        scanner.scanOnce();

        assertThat(received).hasSize(1);
        ReportFactProducer.ReportCheckpoint checkpoint = received.get(0);
        assertThat(checkpoint.taskId()).isEqualTo("task-1");
        assertThat(checkpoint.phaseId()).isEqualTo("phase-1");
        assertThat(checkpoint.tenantCode()).isEqualTo("XSJ");
        assertThat(checkpoint.ownerKey()).isEqualTo("owner-1");
        assertThat(checkpoint.payloadJson()).contains("昨日用电简报");
    }

    private static final class MemoryPhase implements TaskPhaseStore {
        private TaskPhase phase = new TaskPhase("task-1", "phase-1", 1, "company", "{\"read\":true}", DUE, "PENDING");

        @Override public List<TaskPhase> due(Instant now, int limit) { return List.of(phase); }
        @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) {
            phase = new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1, phase.source(), phase.payloadJson(), phase.dueAt(), "EXECUTING");
            return true;
        }
        @Override public Optional<TaskPhase> find(String phaseId) { return Optional.of(phase); }
        @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) {
            phase = new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1, phase.source(), payloadJson, phase.dueAt(), status);
            return true;
        }
        @Override public int recoverExecuting(Instant at) { return 0; }
        @Override public boolean allTerminal(String taskId) { return true; }
        @Override public List<String> outcomes(String taskId) { return List.of("COMPLETED"); }
    }

    private static final class MemoryTasks implements TaskFactStore {
        private TaskFact task;
        private MemoryTasks(TaskFact task) { this.task = task; }
        @Override public TaskFact save(TaskFact value) { task = value; return value; }
        @Override public Optional<TaskFact> find(String taskId) { return Optional.of(task); }
        @Override public Optional<TaskFact> findByIdempotencyKey(String key) { return Optional.of(task); }
        @Override public Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
            if (task.version() != expectedVersion) return Optional.empty();
            task = replacement;
            return Optional.of(replacement);
        }
    }

    private static final class Notifications implements TaskNotificationStore {
        @Override public TaskNotificationFact record(TaskNotificationFact notification) { return notification; }
        @Override public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before) { return List.of(); }
    }
}
