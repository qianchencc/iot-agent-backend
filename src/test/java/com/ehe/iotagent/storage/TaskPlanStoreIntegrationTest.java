package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.ehe.iotagent.task.TaskPlanFact;
import com.ehe.iotagent.task.TaskPlanOccurrence;
import com.ehe.iotagent.task.TaskPlanStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable cancellation/occurrence matrix; enabled only against an explicitly configured fixture DB. */
@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=true",
        "agent.storage.redis.enabled=false",
        "iot.identity.verify=false",
        "agent.heartbeat-ms=0",
        "agent.task.scanner.poll-ms=3600000",
        "spring.datasource.url=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "spring.datasource.username=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "spring.datasource.password=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"
})
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AGENT_TASK_FIXTURE_MYSQL_PASSWORD", matches = ".+")
class TaskPlanStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T09:00:00Z");

    @Autowired
    private TaskPlanStore plans;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private com.ehe.iotagent.task.TaskProposalStore proposals;
    @Autowired
    private com.ehe.iotagent.task.TaskPhaseStore phases;
    @Autowired
    private com.ehe.iotagent.task.TaskNotificationRetryStore notificationRetries;
    @Autowired
    private com.ehe.iotagent.task.TaskNotificationStore notifications;
    @Autowired
    private com.ehe.iotagent.task.TaskFactStore tasks;
    @Autowired
    private com.ehe.iotagent.task.TaskAtomicTransactions atomic;

    @Test
    void intervalSelectionCrossesFuturePagesAndComparesOffsetInstants() throws Exception {
        String prefix = "interval-pages-" + UUID.randomUUID() + "-";
        java.sql.Timestamp updated = java.sql.Timestamp.from(Instant.parse("2020-01-01T00:00:00Z"));
        try {
            for (int index = 0; index < 62; index++) {
                boolean started = index == 60;
                boolean matured = index == 61;
                String payload = mapper.writeValueAsString(java.util.Map.of("source", "office",
                        "interval", java.util.Map.of(
                                "startsAt", (started || matured ? NOW.minusSeconds(60) : NOW.plusSeconds(3600))
                                        .atOffset(java.time.ZoneOffset.ofHours(8)).toString(),
                                "endsAt", (matured ? NOW.minusSeconds(10) : NOW.plusSeconds(7200)).toString())));
                String id = prefix + index;
                jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time)"
                                + " VALUES (?,1,?,?,?,?,?)",
                        id, matured ? "EXECUTING" : "SCHEDULED", payload, id, updated, updated);
                if (matured) {
                    jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) "
                                    + "VALUES (?,?,'office',1,'COMPLETED',?,'{}',NOW(),NOW())",
                            id + "-done", id, java.sql.Timestamp.from(NOW.minusSeconds(20)));
                }
            }
            assertThat(phases.startedIntervals(NOW, 25)).contains(prefix + "60").doesNotContain(prefix + "0");
            assertThat(phases.readyToFinalize(NOW, 25)).contains(prefix + "61").doesNotContain(prefix + "60");
        } finally {
            jdbc.update("DELETE FROM task_phases WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM task_facts WHERE task_id LIKE ?", prefix + "%");
        }
    }

    @Test
    void finishedOrdinaryTaskIsReconciledWithoutTreatingEmptyOrWaitingTasksAsFinished() {
        String prefix = "task-close-" + UUID.randomUUID() + "-";
        try {
            insertTaskWithPhase(prefix + "done", prefix + "done-phase", "COMPLETED", java.sql.Timestamp.from(NOW));
            insertTaskWithPhase(prefix + "waiting", prefix + "waiting-phase", "PENDING", java.sql.Timestamp.from(NOW.plusSeconds(60)));
            jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                            + "VALUES (?,1,'SCHEDULED','{}',?,NOW(),NOW())", prefix + "empty", prefix + "empty");
            assertThat(phases.readyToFinalize(NOW, 25))
                    .contains(prefix + "done").doesNotContain(prefix + "waiting", prefix + "empty");
        } finally {
            jdbc.update("DELETE FROM task_phases WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM task_facts WHERE task_id LIKE ?", prefix + "%");
        }
    }

    @Test
    void terminalTaskWithoutRetryRowIsFoundButImmediateAndAlreadyNotifiedTasksAreExcluded() {
        String prefix = "notice-" + UUID.randomUUID() + "-";
        try {
            for (String kind : java.util.List.of("missing", "immediate", "notified")) {
                String id = prefix + kind;
                insertTaskWithPhase(id, id + "-phase", "COMPLETED", java.sql.Timestamp.from(NOW));
                jdbc.update("UPDATE task_facts SET status='COMPLETED' WHERE task_id=?", id);
            }
            jdbc.update("UPDATE task_facts SET payload_json=JSON_SET(payload_json,'$.sourceType','immediate_draft') WHERE task_id=?", prefix + "immediate");
            notifications.record(new com.ehe.iotagent.task.TaskNotificationFact(prefix + "notice", prefix + "notified",
                    "LOCAL", "fixture", "office", "COMPLETED", NOW, null, "{}"));
            assertThat(notificationRetries.pending(25)).extracting(com.ehe.iotagent.task.TaskFact::taskId)
                    .contains(prefix + "missing").doesNotContain(prefix + "immediate", prefix + "notified");
        } finally {
            jdbc.update("DELETE FROM task_notifications WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM task_notification_attempts WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM task_phases WHERE task_id LIKE ?", prefix + "%");
            jdbc.update("DELETE FROM task_facts WHERE task_id LIKE ?", prefix + "%");
        }
    }

    @Test
    void notificationFailureRollsBackParentThenNextScanClosesWithoutRedispatch() {
        String id = "atomic-close-" + UUID.randomUUID();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            insertTaskWithPhase(id, id + "-phase", "COMPLETED", java.sql.Timestamp.from(NOW));
            jdbc.update("UPDATE task_facts SET status='EXECUTING' WHERE task_id=?", id);
            var unreliableInbox = new com.ehe.iotagent.task.TaskNotificationStore() {
                @Override public com.ehe.iotagent.task.TaskNotificationFact record(com.ehe.iotagent.task.TaskNotificationFact notification) {
                    if (id.equals(notification.taskId()) && fail.getAndSet(false)) throw new IllegalStateException("inbox temporarily unavailable");
                    return notifications.record(notification);
                }
                @Override public java.util.Optional<com.ehe.iotagent.task.TaskNotificationFact> find(String notificationId) {
                    return notifications.find(notificationId);
                }
                @Override public java.util.List<com.ehe.iotagent.task.TaskNotificationFact> list(String tenant, String recipient, String source, int limit, String before) {
                    return notifications.list(tenant, recipient, source, limit, before);
                }
            };
            var scanner = new com.ehe.iotagent.task.TaskScanner(phases, tasks,
                    request -> { throw new AssertionError("finished phase must never be dispatched again"); },
                    unreliableInbox, (checkpoint, identity) -> java.util.Optional.empty(),
                    null, null, notificationRetries, null, null, atomic, null,
                    java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
            scanner.scanOnce();
            assertThat(tasks.find(id).orElseThrow().status()).isEqualTo("EXECUTING");
            assertThat(notifications.list("LOCAL", "fixture", "office", 100, null))
                    .noneMatch(notification -> id.equals(notification.taskId()));
            scanner.scanOnce();
            assertThat(tasks.find(id).orElseThrow().status()).isEqualTo("COMPLETED");
            assertThat(notifications.list("LOCAL", "fixture", "office", 100, null))
                    .filteredOn(notification -> id.equals(notification.taskId())).hasSize(1);
            assertThat(phases.find(id + "-phase").orElseThrow().status()).isEqualTo("COMPLETED");
        } finally {
            jdbc.update("DELETE FROM task_notifications WHERE task_id=?", id);
            jdbc.update("DELETE FROM task_notification_attempts WHERE task_id=?", id);
            jdbc.update("DELETE FROM task_phases WHERE task_id=?", id);
            jdbc.update("DELETE FROM task_facts WHERE task_id=?", id);
        }
    }

    @Test
    void dueQueueAdmitsSixTasksButNeverOvertakesAnExecutingPredecessor() {
        String suffix = UUID.randomUUID().toString();
        var taskIds = java.util.stream.IntStream.range(0, 7)
                .mapToObj(index -> "concurrency-" + index + "-" + suffix).toList();
        Instant cutoff = Instant.now().plusSeconds(3600);
        java.sql.Timestamp firstAt = java.sql.Timestamp.from(cutoff.minusSeconds(60));
        try {
            for (String id : taskIds) insertTaskWithPhase(id, id + "-first", "PENDING", firstAt);
            String firstTask = taskIds.get(0);
            jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) "
                            + "VALUES (?,?,?,1,'PENDING',?,'{}',NOW(),NOW())",
                    firstTask + "-next", firstTask, "office", java.sql.Timestamp.from(cutoff.minusSeconds(50)));
            var ready = phases.due(cutoff, 6);
            assertThat(ready).extracting(com.ehe.iotagent.task.TaskPhaseStore.TaskPhase::phaseId)
                    .containsExactlyElementsOf(taskIds.subList(0, 6).stream().map(id -> id + "-first").toList());
            var first = ready.get(0);
            assertThat(phases.claim(first.phaseId(), first.version(), cutoff)).isTrue();
            assertThat(phases.due(cutoff, 6))
                    .extracting(com.ehe.iotagent.task.TaskPhaseStore.TaskPhase::phaseId)
                    .containsExactlyElementsOf(taskIds.subList(1, 7).stream().map(id -> id + "-first").toList());
        } finally {
            for (String id : taskIds) {
                jdbc.update("DELETE FROM task_phases WHERE task_id=?", id);
                jdbc.update("DELETE FROM task_facts WHERE task_id=?", id);
            }
        }
    }

    @Test
    void newExpressionRetiresPendingCardsOnlyWithinConversationOwnerAndSource() throws Exception {
        String conversation = UUID.randomUUID().toString();
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String kind : java.util.List.of("TASK", "CHANGE", "CANCELLATION")) {
            for (String variant : java.util.List.of("pending", "expired", "confirmed", "other-owner", "other-source")) {
                String id = conversation + "-" + kind + "-" + variant;
                ids.add(id);
                String owner = variant.equals("other-owner") ? "other" : "fixture";
                String source = variant.equals("other-source") ? "office" : "company";
                String status = variant.equals("confirmed") ? "CONFIRMED"
                        : variant.equals("expired") ? "EXPIRED" : "PENDING_CONFIRMATION";
                String payload = mapper.writeValueAsString(java.util.Map.of(
                        "tenantCode", "LOCAL", "ownerKey", owner, "source", source,
                        "conversationId", conversation, "turnId", "old-turn"));
                proposals.save(new com.ehe.iotagent.task.TaskProposalFact(id, 3, status, payload, id,
                        kind, "{}", "{}", "{}", "create", "LOCAL", owner, source, NOW.plusSeconds(600)));
            }
        }
        try {
            var retired = proposals.retireForConversation(conversation, "LOCAL", "fixture", "company");
            assertThat(retired).extracting(com.ehe.iotagent.task.TaskProposalFact::proposalId)
                    .containsExactlyInAnyOrderElementsOf(ids.stream().filter(id -> id.endsWith("-pending") || id.endsWith("-expired")).toList());
            for (String id : ids) {
                var current = proposals.find(id).orElseThrow();
                boolean changed = id.endsWith("-pending") || id.endsWith("-expired");
                assertThat(current.status()).isEqualTo(changed ? "CANCELLED"
                        : id.endsWith("-confirmed") ? "CONFIRMED" : "PENDING_CONFIRMATION");
                assertThat(current.version()).isEqualTo(changed ? 4 : 3);
                assertThat(current.payloadJson()).contains("old-turn");
            }
            assertThat(proposals.retireForConversation(conversation, "LOCAL", "fixture", "company")).isEmpty();
        } finally {
            jdbc.update("DELETE FROM task_proposals WHERE conversation_id=?", conversation);
        }
    }

    @Test
    void cancellationCancelsEveryMaterializedActiveTaskAndPreservesPhaseHistory() {
        String suffix = UUID.randomUUID().toString();
        String planId = "ticket19-cancel-" + suffix;
        String payload = planPayload("2030-11-01T10:00:00Z");
        plans.save(new TaskPlanFact(planId, 1, "ENABLED", payload, "idem-" + suffix));
        insertTaskWithPhase("pending-" + suffix, "phase-pending-" + suffix, "PENDING", "2030-11-01 10:00:00");
        insertTaskWithPhase("executing-" + suffix, "phase-executing-" + suffix, "EXECUTING", "2030-11-01 10:01:00");
        insertTaskWithPhase("unknown-" + suffix, "phase-unknown-" + suffix, "UNKNOWN", "2030-11-01 10:02:00");
        insertTaskWithPhase("terminal-" + suffix, "phase-terminal-" + suffix, "COMPLETED", "2030-11-01 10:03:00");
        // A partially completed occurrence still cancels as a whole: its executed
        // checkpoint stays durable history while the pending remainder stops.
        insertTaskWithPhase("partial-" + suffix, "phase-partial-done-" + suffix, "COMPLETED", "2030-11-02 10:00:00");
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) VALUES (?,?,?,1,?,?,?,NOW(),NOW())",
                "phase-partial-rest-" + suffix, "partial-" + suffix, "office", "PENDING", "2030-11-02 10:05:00",
                "{\"tenantCode\":\"LOCAL\",\"ownerKey\":\"fixture\",\"source\":\"office\",\"phases\":[]}");
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 1), "pending-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 2), "executing-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 3), "unknown-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 4), "terminal-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 5), "partial-" + suffix);

        TaskPlanFact cancelled = plans.cancelAllIfVersion(planId, 1, NOW).orElseThrow();

        assertThat(cancelled.status()).isEqualTo("DELETED");
        assertThat(cancelled.version()).isEqualTo(2);
        assertThat(phaseStatus("phase-pending-" + suffix)).isEqualTo("CANCELLED");
        assertThat(phaseStatus("phase-executing-" + suffix)).isEqualTo("EXECUTING");
        assertThat(phaseStatus("phase-unknown-" + suffix)).isEqualTo("UNKNOWN");
        assertThat(phaseStatus("phase-terminal-" + suffix)).isEqualTo("COMPLETED");
        assertThat(phaseStatus("phase-partial-done-" + suffix)).isEqualTo("COMPLETED");
        assertThat(phaseStatus("phase-partial-rest-" + suffix)).isEqualTo("CANCELLED");
        // Runtime store.cancelPlan: every still-scheduled/executing materialized
        // task fact turns CANCELLED — including the in-flight and partially
        // completed occurrences — while executed phase history stays untouched.
        assertThat(taskStatus("pending-" + suffix)).isEqualTo("CANCELLED");
        assertThat(taskStatus("executing-" + suffix)).isEqualTo("CANCELLED");
        assertThat(taskStatus("unknown-" + suffix)).isEqualTo("CANCELLED");
        assertThat(taskStatus("terminal-" + suffix)).isEqualTo("CANCELLED");
        assertThat(taskStatus("partial-" + suffix)).isEqualTo("CANCELLED");
        assertThat(plans.cancelAllIfVersion(planId, 1, NOW)).isEmpty();
    }

    @Test
    void occurrenceCasMatrixRejectsPastInvalidAndMaterializedRows() {
        String suffix = UUID.randomUUID().toString();
        String planId = "ticket19-occurrence-" + suffix;
        plans.save(new TaskPlanFact(planId, 1, "ENABLED", planPayload("2030-11-01T10:00:00Z"), "idem-" + suffix));
        String override = "{\"source\":\"office\",\"phases\":[{\"at\":\"2030-11-02T12:00:00Z\",\"actions\":[]}]" + "}";

        TaskPlanOccurrence skipped = plans.overrideOccurrenceIfVersion(planId, LocalDate.of(2030, 11, 2), 1, null, NOW).orElseThrow();
        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.planVersion()).isEqualTo(2);
        assertThat(plans.overrideOccurrenceIfVersion(planId, LocalDate.of(2030, 11, 2), 1, override, NOW)).isEmpty();
        assertThat(plans.overrideOccurrenceIfVersion(planId, LocalDate.of(2020, 1, 1), 2, null, NOW)).isEmpty();
        assertThat(plans.overrideOccurrenceIfVersion(planId, LocalDate.of(2030, 11, 3), 2,
                "{\"phases\":[{\"at\":\"2020-01-01T00:00:00Z\",\"actions\":[]}]}", NOW)).isEmpty();

        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 4), "already-materialized-" + suffix);
        assertThat(plans.overrideOccurrenceIfVersion(planId, LocalDate.of(2030, 11, 4), 2, override, NOW)).isEmpty();
    }

    private void insertTaskWithPhase(String taskId, String phaseId, String phaseStatus, Object dueAt) {
        String payload = "{\"tenantCode\":\"LOCAL\",\"ownerKey\":\"fixture\",\"source\":\"office\",\"phases\":[{\"phaseId\":\""
                + phaseId + "\",\"at\":\"2030-11-01T10:00:00Z\",\"actions\":[]}]" + "}";
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?, ?,?,NOW(),NOW())",
                taskId, 1, "SCHEDULED", payload, "idem-" + taskId);
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) VALUES (?,?,?,1,?,?,?,NOW(),NOW())",
                phaseId, taskId, "office", phaseStatus, dueAt, payload);
    }

    private String phaseStatus(String phaseId) {
        return jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id=?", String.class, phaseId);
    }

    private String taskStatus(String taskId) {
        return jdbc.queryForObject("SELECT status FROM task_facts WHERE task_id=?", String.class, taskId);
    }

    private String planPayload(String startsAt) {
        var root = mapper.createObjectNode();
        root.put("source", "office").put("tenantCode", "LOCAL").put("ownerKey", "fixture");
        root.putObject("repeat").put("frequency", "daily");
        root.putObject("interval").put("startsAt", startsAt).put("endsAt", "2030-11-30T10:00:00Z");
        root.putArray("phases").addObject().put("at", startsAt).putArray("actions");
        return root.toString();
    }
}
