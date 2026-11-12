package com.ehe.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US11 automatic-execution audit contract: executed, failed and interrupted
 * checkpoints and the terminal task outcome become durable audit events
 * attributed to the system actor, projected only from server-stored task
 * facts; a stored creator principal is copied verbatim and an absent one stays
 * unresolved instead of being borrowed from the allowlist or the model. Audit
 * failure never redispatches a device, never claims durable audit success, and
 * the terminal outcome fact plus its audit row commit or roll back together.
 */
class TaskScannerAuditContractTest {
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void automaticExecutionIsAuditedAsSystemActorWithStoredCreatorFactsOnly() throws Exception {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-ok", "phase-ok", NOW, "company"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-ok", "SCHEDULED"));
        MemoryAudit audit = new MemoryAudit();
        TaskScanner scanner = scanner(phases, tasks, new CountingGateway(), new MemoryNotifications(),
                null, audit);

        scanner.scanOnce();

        AuditEventFact checkpoint = audit.byType("TASK_CHECKPOINT_EXECUTED");
        JsonNode body = MAPPER.readTree(checkpoint.payloadJson());
        assertThat(checkpoint.tenantCode()).isEqualTo("TENANT");
        assertThat(body.path("actor").asText()).isEqualTo("system");
        assertThat(body.path("action").asText()).isEqualTo("execute_checkpoint");
        assertThat(body.path("source").asText()).isEqualTo("company");
        assertThat(body.path("taskId").asText()).isEqualTo("task-ok");
        assertThat(body.path("phaseId").asText()).isEqualTo("phase-ok");
        assertThat(body.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(body.path("dispatchStarted").asBoolean()).isTrue();
        assertThat(body.path("creatorName").asText()).isEqualTo("值班张三");
        assertThat(body.path("ownerKey").asText()).isEqualTo("owner-1");
        assertThat(body.path("conversationId").asText()).isEqualTo("conv-1");
        assertThat(body.path("turnId").asText()).isEqualTo("turn-1");
        assertThat(body.path("label").asText()).isEqualTo("夜间关灯");
        assertThat(body.has("principalId"))
                .as("确认任务载荷不存储已核验主体，自动执行不得伪造创建者身份").isFalse();
        assertThat(body.has("identityRealm")).isFalse();
        assertThat(body.path("counts").path("devices").asInt()).isEqualTo(2);
        assertThat(body.path("counts").path("verified").asInt()).isEqualTo(1);
        assertThat(body.path("counts").path("unconfirmed").asInt()).isEqualTo(1);
        assertThat(body.path("result").path("receipts").size()).isEqualTo(2);
        assertThat(body.path("result").path("receipts").get(0).path("actionId").asText()).isEqualTo("dev-1");
        assertThat(body.path("result").path("receipts").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("result").path("readback").path("dev-1").asText()).isEqualTo("confirmed");
        assertThat(body.path("result").path("readback").path("dev-2").asText()).isEqualTo("unconfirmed");
        assertThat(checkpoint.createdAt()).isEqualTo(NOW);

        AuditEventFact outcome = audit.byType("TASK_OUTCOME_RECORDED");
        JsonNode outcomeBody = MAPPER.readTree(outcome.payloadJson());
        assertThat(outcomeBody.path("actor").asText()).isEqualTo("system");
        assertThat(outcomeBody.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(outcomeBody.path("taskId").asText()).isEqualTo("task-ok");
        assertThat(outcomeBody.path("endedAt").asText()).isEqualTo(NOW.toString());
        assertThat(outcomeBody.path("principalId").asText("")).isEmpty();

        int stableEvents = audit.byEvent.size();
        scanner.scanOnce();
        assertThat(audit.byEvent.size())
                .as("重试扫描不得为已稳定转换追加重复事件").isEqualTo(stableEvents);
    }

    @Test
    void systemExecutionStaysVisibleInTheVerifiedCreatorsMemberTimeline() throws Exception {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("member-task", "member-phase", NOW, "company"));
        MemoryTasks tasks = new MemoryTasks();
        TaskFact original = task("member-task", "SCHEDULED");
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(original.payloadJson());
        payload.put("principalId", "creator-1").put("identityRealm", "https://identity.example/");
        tasks.put(new TaskFact(original.taskId(), original.version(), original.status(),
                payload.toString(), original.idempotencyKey()));
        MemoryAudit audit = new MemoryAudit();
        scanner(phases, tasks, new CountingGateway(), new MemoryNotifications(), null, audit).scanOnce();
        var timeline = audit.query(new com.ehe.iotagent.audit.AuditEventQuery("TENANT",
                "https://identity.example/", "creator-1", "company", null, false,
                null, null, java.util.Set.of(), java.util.Set.of(), false, null, 0, 50));
        assertThat(timeline).extracting(AuditEventFact::eventType)
                .containsExactlyInAnyOrder("TASK_CHECKPOINT_EXECUTED", "TASK_OUTCOME_RECORDED");
        for (AuditEventFact event : timeline) {
            assertThat(MAPPER.readTree(event.payloadJson()).path("actor").asText()).isEqualTo("system");
        }
    }

    @Test
    void failedAndInterruptedCheckpointsAreAuditedWithReasons() throws Exception {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-int", "phase-int", NOW, "office"));
        phases.add(phase("task-failed", "phase-failed", NOW, "office"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-int", "SCHEDULED"));
        tasks.put(task("task-failed", "SCHEDULED"));
        MemoryAudit audit = new MemoryAudit();
        TaskExecutionGateway gateway = request -> {
            if ("phase-int".equals(request.phaseId())) {
                throw new IllegalStateException("上游控制请求中断");
            }
            throw new TaskExecutionGateway.DispatchException("任务执行已停止", false);
        };
        TaskScanner scanner = scanner(phases, tasks, gateway, new MemoryNotifications(), null, audit);

        scanner.scanOnce();

        JsonNode interrupted = MAPPER.readTree(audit.byTypeAndTask("TASK_CHECKPOINT_EXECUTED", "task-int").payloadJson());
        assertThat(interrupted.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.path("reason").asText()).isEqualTo("上游控制请求中断");
        assertThat(interrupted.path("dispatchStarted").asBoolean()).isTrue();
        assertThat(interrupted.path("source").asText()).isEqualTo("company");
        JsonNode failed = MAPPER.readTree(audit.byTypeAndTask("TASK_CHECKPOINT_EXECUTED", "task-failed").payloadJson());
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("reason").asText()).isEqualTo("任务执行已停止");
        assertThat(failed.path("dispatchStarted").asBoolean()).isFalse();
        assertThat(MAPPER.readTree(audit.byTypeAndTask("TASK_OUTCOME_RECORDED", "task-int").payloadJson())
                .path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(MAPPER.readTree(audit.byTypeAndTask("TASK_OUTCOME_RECORDED", "task-failed").payloadJson())
                .path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void restartRecoveryAuditsMissedAndInterruptedCheckpointsWithoutDispatch() throws Exception {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-rec", "phase-rec", NOW, "company"));
        assertThat(phases.claim("phase-rec", 1, NOW)).isTrue();
        phases.add(phase("task-miss", "phase-miss", NOW.minusSeconds(7200), "company"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-rec", "EXECUTING"));
        tasks.put(task("task-miss", "SCHEDULED"));
        MemoryAudit audit = new MemoryAudit();
        CountingGateway gateway = new CountingGateway();
        TaskScanner scanner = scanner(phases, tasks, gateway, new MemoryNotifications(), null, audit);

        int recovered = scanner.recover();

        assertThat(recovered).isEqualTo(2);
        assertThat(gateway.executions)
                .as("恢复不得重新下发任何检查点").isZero();
        JsonNode interrupted = MAPPER.readTree(audit.byTypeAndPhase("TASK_CHECKPOINT_EXECUTED", "phase-rec").payloadJson());
        assertThat(interrupted.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.path("reason").asText()).isEqualTo("service_restart");
        assertThat(interrupted.has("dispatchStarted"))
                .as("重启后是否已过设备边界不可知，不得虚构下发事实").isFalse();
        JsonNode missed = MAPPER.readTree(audit.byTypeAndPhase("TASK_CHECKPOINT_EXECUTED", "phase-miss").payloadJson());
        assertThat(missed.path("status").asText()).isEqualTo("MISSED");
        assertThat(missed.path("dispatchStarted").asBoolean()).isFalse();
        assertThat(MAPPER.readTree(audit.byTypeAndTask("TASK_OUTCOME_RECORDED", "task-rec").payloadJson())
                .path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(MAPPER.readTree(audit.byTypeAndTask("TASK_OUTCOME_RECORDED", "task-miss").payloadJson())
                .path("status").asText()).isEqualTo("MISSED");
    }

    @Test
    void auditOutageKeepsExecutionDurableAndTerminalOutcomeWaitsForAudit() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-audit-down", "phase-down", NOW, "company"));
        phases.ready.add("task-audit-down");
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-audit-down", "SCHEDULED"));
        MemoryAudit audit = new MemoryAudit();
        audit.fail = true;
        MemoryNotifications notifications = new MemoryNotifications();
        CountingGateway gateway = new CountingGateway();
        TaskScanner scanner = scanner(phases, tasks, gateway, notifications,
                new SnapshotTransactions(tasks, notifications), audit);

        scanner.scanOnce();

        assertThat(gateway.executions).isEqualTo(1);
        assertThat(phases.status("phase-down")).isEqualTo("COMPLETED");
        assertThat(tasks.find("task-audit-down").orElseThrow().status())
                .as("审计失败时终态事务必须回滚，不得记录未审计结果").isEqualTo("EXECUTING");
        assertThat(notifications.byTask).isEmpty();
        assertThat(audit.byTypeOrNull("TASK_CHECKPOINT_EXECUTED")).isNull();
        assertThat(audit.byTypeOrNull("TASK_OUTCOME_RECORDED")).isNull();

        audit.fail = false;
        scanner.scanOnce();

        assertThat(gateway.executions)
                .as("审计失败绝不得触发设备重新下发").isEqualTo(1);
        assertThat(tasks.find("task-audit-down").orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(audit.byType("TASK_OUTCOME_RECORDED").eventType()).isEqualTo("TASK_OUTCOME_RECORDED");
        JsonNode recovered;
        try { recovered = MAPPER.readTree(audit.byType("TASK_CHECKPOINT_EXECUTED").payloadJson()); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
        assertThat(recovered.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(recovered.path("result").path("readback").path("dev-2").asText()).isEqualTo("unconfirmed");

        scanner.scanOnce();
        assertThat(audit.byEvent.size())
                .as("审计恢复后的重试扫描不得重复稳定转换事件").isEqualTo(2);
    }

    @Test
    void immediateDraftsStayActionHistoryAndNeverEnterTaskAudit() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-draft", "phase-draft", NOW, "office"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(new TaskFact("task-draft", 1, "SCHEDULED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"sourceType\":\"immediate_draft\",\"creatorName\":\"值班张三\"}",
                "task-draft"));
        MemoryAudit audit = new MemoryAudit();
        TaskScanner scanner = scanner(phases, tasks, new CountingGateway(), new MemoryNotifications(), null, audit);

        scanner.scanOnce();

        assertThat(audit.byEvent)
                .as("即时草案属动作历史，由草案模块审计，任务审计不得复制").isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private static TaskScanner scanner(MemoryPhases phases, MemoryTasks tasks, TaskExecutionGateway gateway,
                                       MemoryNotifications notifications, TaskAtomicTransactions atomic,
                                       AuditEventStore audit) {
        return new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, atomic, audit, CLOCK);
    }

    private static TaskPhaseStore.TaskPhase phase(String taskId, String phaseId, Instant dueAt, String source) {
        return new TaskPhaseStore.TaskPhase(taskId, phaseId, 1, source,
                "{\"at\":\"" + dueAt + "\",\"actions\":[]}", dueAt, "PENDING");
    }

    private static TaskFact task(String taskId, String status) {
        return new TaskFact(taskId, 1, status,
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"creatorName\":\"值班张三\",\"label\":\"夜间关灯\","
                        + "\"conversationId\":\"conv-1\",\"turnId\":\"turn-1\"}",
                taskId);
    }

    private static final class CountingGateway implements TaskExecutionGateway {
        int executions;

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            executions++;
            return new TaskExecutionResult("COMPLETED",
                    "{\"outcome\":\"COMPLETED\",\"executedAt\":\"" + NOW + "\",\"dispatchStarted\":true,"
                            + "\"devices\":{"
                            + "\"dev-1\":{\"deviceId\":\"dev-1\",\"outcome\":\"verified\",\"receipt\":\"ACCEPTED\",\"readback\":\"confirmed\"},"
                            + "\"dev-2\":{\"deviceId\":\"dev-2\",\"outcome\":\"unconfirmed\",\"receipt\":\"ACCEPTED\","
                            + "\"readback\":\"unconfirmed\",\"reason\":\"指令已受理，尚未取得平台实测回读\"}}}",
                    true);
        }
    }

    /** Mirrors the production store: same event id keeps the original row, duplicates never overwrite. */
    private static final class MemoryAudit implements AuditEventStore {
        final Map<String, AuditEventFact> byEvent = new java.util.concurrent.ConcurrentHashMap<>();
        boolean fail;

        @Override public AuditEventFact append(AuditEventFact event) {
            if (fail) throw new IllegalStateException("审计存储不可用");
            return byEvent.merge(event.eventId(), event, (existing, duplicate) -> existing);
        }
        @Override public List<AuditEventFact> readByTenant(String tenantCode) {
            return byEvent.values().stream().filter(event -> tenantCode.equals(event.tenantCode())).toList();
        }
        @Override public Optional<AuditEventFact> findByEventId(String eventId) {
            return Optional.ofNullable(byEvent.get(eventId));
        }
        @Override public List<String> tenants() { return List.of("TENANT"); }

        AuditEventFact byType(String eventType) {
            AuditEventFact found = byTypeOrNull(eventType);
            assertThat(found).as("缺少事件 " + eventType).isNotNull();
            return found;
        }
        AuditEventFact byTypeOrNull(String eventType) {
            return byEvent.values().stream().filter(event -> eventType.equals(event.eventType())).findFirst().orElse(null);
        }
        AuditEventFact byTypeAndTask(String eventType, String taskId) {
            return byEvent.values().stream().filter(event -> eventType.equals(event.eventType())
                            && payloadTaskId(event).equals(taskId)).findFirst().orElseThrow();
        }
        AuditEventFact byTypeAndPhase(String eventType, String phaseId) {
            return byEvent.values().stream().filter(event -> eventType.equals(event.eventType())
                            && payloadPhaseId(event).equals(phaseId)).findFirst().orElseThrow();
        }
        private String payloadTaskId(AuditEventFact event) {
            try {
                return MAPPER.readTree(event.payloadJson()).path("taskId").asText("");
            } catch (Exception invalid) {
                return "";
            }
        }
        private String payloadPhaseId(AuditEventFact event) {
            try {
                return MAPPER.readTree(event.payloadJson()).path("phaseId").asText("");
            } catch (Exception invalid) {
                return "";
            }
        }
    }

    /** Transaction double with real rollback for the in-memory stores, like the shared JDBC transaction. */
    private static final class SnapshotTransactions implements TaskAtomicTransactions {
        private final MemoryTasks tasks;
        private final MemoryNotifications notifications;

        private SnapshotTransactions(MemoryTasks tasks, MemoryNotifications notifications) {
            this.tasks = tasks;
            this.notifications = notifications;
        }

        @Override public <T> T write(Supplier<T> action) {
            Map<String, TaskFact> taskSnapshot = tasks.copy();
            Map<String, TaskNotificationFact> notificationSnapshot = notifications.copy();
            try {
                return action.get();
            } catch (RuntimeException failure) {
                tasks.restore(taskSnapshot);
                notifications.restore(notificationSnapshot);
                throw failure;
            }
        }
    }

    private static final class MemoryTasks implements TaskFactStore {
        private final Map<String, TaskFact> values = new LinkedHashMap<>();

        void put(TaskFact task) { values.put(task.taskId(), task); }

        Map<String, TaskFact> copy() { return new LinkedHashMap<>(values); }

        void restore(Map<String, TaskFact> snapshot) {
            values.clear();
            values.putAll(snapshot);
        }

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
        private final Map<String, TaskPhase> byPhase = new LinkedHashMap<>();
        private final Map<String, PhaseResult> results = new LinkedHashMap<>();
        @Override public List<PhaseResult> terminalPhases(String taskId) {
            return results.values().stream().filter(row -> taskId.equals(row.taskId())).toList();
        }
        final List<String> ready = new ArrayList<>();

        void add(TaskPhase phase) { byPhase.put(phase.phaseId(), phase); }

        String status(String phaseId) {
            TaskPhase phase = byPhase.get(phaseId);
            return phase == null ? null : phase.status();
        }

        @Override public List<TaskPhase> due(Instant now, int limit) {
            List<TaskPhase> due = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"PENDING".equals(phase.status()) || phase.dueAt().isAfter(now)) continue;
                if (due.size() >= limit) break;
                due.add(phase);
            }
            return due;
        }
        @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || phase.version() != expectedVersion || !"PENDING".equals(phase.status())) {
                return false;
            }
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "EXECUTING"));
            return true;
        }
        @Override public Optional<TaskPhase> find(String phaseId) { return Optional.ofNullable(byPhase.get(phaseId)); }
        @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || phase.version() != expectedVersion) return false;
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), status));
            results.put(phaseId, new PhaseResult(phase.taskId(), phaseId, phase.dueAt(), status,
                    phase.payloadJson(), payloadJson, NOW, endedAt));
            return true;
        }
        @Override public int recoverExecuting(Instant at) { return recoverInterrupted(at).size(); }
        @Override public List<TaskPhase> recoverInterrupted(Instant at) {
            List<TaskPhase> recovered = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"EXECUTING".equals(phase.status())) continue;
                TaskPhase closed = new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                        phase.source(), phase.payloadJson(), phase.dueAt(), "INTERRUPTED");
                byPhase.put(phase.phaseId(), closed);
                recovered.add(closed);
            }
            return recovered;
        }
        @Override public boolean miss(String phaseId, Instant at, String reason) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || !"PENDING".equals(phase.status())) return false;
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "MISSED"));
            return true;
        }
        @Override public List<TaskPhase> missElapsed(Instant at) {
            List<TaskPhase> missed = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"PENDING".equals(phase.status()) || !phase.dueAt().isBefore(at)) continue;
                missed.add(new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                        phase.source(), phase.payloadJson(), phase.dueAt(), "MISSED"));
            }
            for (TaskPhase phase : missed) {
                byPhase.put(phase.phaseId(), phase);
            }
            return missed;
        }
        @Override public List<String> readyToFinalize(Instant now, int limit) { return List.copyOf(ready); }
        @Override public boolean allTerminal(String taskId) {
            return byPhase.values().stream()
                    .noneMatch(phase -> taskId.equals(phase.taskId())
                            && ("PENDING".equals(phase.status()) || "EXECUTING".equals(phase.status())));
        }
        @Override public List<String> outcomes(String taskId) {
            List<String> values = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (taskId.equals(phase.taskId())) values.add(phase.status());
            }
            return values;
        }
    }

    private static final class MemoryNotifications implements TaskNotificationStore {
        final Map<String, TaskNotificationFact> byTask = new LinkedHashMap<>();

        Map<String, TaskNotificationFact> copy() { return new LinkedHashMap<>(byTask); }

        void restore(Map<String, TaskNotificationFact> snapshot) {
            byTask.clear();
            byTask.putAll(snapshot);
        }

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
}
