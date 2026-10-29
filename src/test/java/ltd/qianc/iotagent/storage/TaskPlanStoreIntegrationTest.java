package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanOccurrence;
import ltd.qianc.iotagent.task.TaskPlanStore;
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
        "AGENT_MYSQL_URL=${AGENT_TASK_FIXTURE_MYSQL_URL}",
        "AGENT_MYSQL_USERNAME=${AGENT_TASK_FIXTURE_MYSQL_USERNAME}",
        "AGENT_MYSQL_PASSWORD=${AGENT_TASK_FIXTURE_MYSQL_PASSWORD}"
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

    @Test
    void cancellationCancelsOnlyPendingMaterializedWorkAndPreservesHistory() {
        String suffix = UUID.randomUUID().toString();
        String planId = "ticket19-cancel-" + suffix;
        String payload = planPayload("2030-11-01T10:00:00Z");
        plans.save(new TaskPlanFact(planId, 1, "ENABLED", payload, "idem-" + suffix));
        insertTaskWithPhase("pending-" + suffix, "phase-pending-" + suffix, "PENDING", "2030-11-01 10:00:00");
        insertTaskWithPhase("executing-" + suffix, "phase-executing-" + suffix, "EXECUTING", "2030-11-01 10:01:00");
        insertTaskWithPhase("unknown-" + suffix, "phase-unknown-" + suffix, "UNKNOWN", "2030-11-01 10:02:00");
        insertTaskWithPhase("terminal-" + suffix, "phase-terminal-" + suffix, "COMPLETED", "2030-11-01 10:03:00");
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 1), "pending-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 2), "executing-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 3), "unknown-" + suffix);
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) VALUES (?,?,?,'MATERIALIZED',1,NOW(),NOW())",
                planId, LocalDate.of(2030, 11, 4), "terminal-" + suffix);

        TaskPlanFact cancelled = plans.cancelAllIfVersion(planId, 1, NOW).orElseThrow();

        assertThat(cancelled.status()).isEqualTo("DELETED");
        assertThat(cancelled.version()).isEqualTo(2);
        assertThat(phaseStatus("phase-pending-" + suffix)).isEqualTo("CANCELLED");
        assertThat(phaseStatus("phase-executing-" + suffix)).isEqualTo("EXECUTING");
        assertThat(phaseStatus("phase-unknown-" + suffix)).isEqualTo("UNKNOWN");
        assertThat(phaseStatus("phase-terminal-" + suffix)).isEqualTo("COMPLETED");
        assertThat(taskStatus("pending-" + suffix)).isEqualTo("CANCELLED");
        assertThat(taskStatus("executing-" + suffix)).isEqualTo("SCHEDULED");
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

    private void insertTaskWithPhase(String taskId, String phaseId, String phaseStatus, String dueAt) {
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
