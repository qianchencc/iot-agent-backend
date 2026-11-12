package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import com.ehe.iotagent.task.TaskScheduler;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTaskSchedulerContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:scheduler_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        scheduler = TaskSchedulerFactory.jdbc(jdbc, mapper);
    }

    @Test
    void reactivatesCancelledFuturePhaseWithoutReplayingTerminalResult() {
        instantiateTask("task-cancelled", "phase-cancelled");
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,result_json,failure_reason,ended_at,create_time,update_time) "
                        + "VALUES (?,?,?,3,'CANCELLED',?,?,?,NULL,NULL,?,NOW(),NOW())",
                "phase-cancelled", "task-cancelled", "office",
                java.sql.Timestamp.from(Instant.parse("2030-01-01T10:00:00Z")),
                "{\"phaseId\":\"phase-cancelled\",\"at\":\"2030-01-01T10:00:00Z\",\"actions\":[]}",
                "{\"phaseId\":\"phase-cancelled\",\"at\":\"2030-01-01T10:00:00Z\",\"actions\":[]}",
                java.sql.Timestamp.from(Instant.parse("2030-01-01T10:00:00Z")));

        scheduler.schedule("task-cancelled", "phase-cancelled", Instant.parse("2030-01-01T11:00:00Z"));

        assertThat(status("phase-cancelled")).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT version FROM task_phases WHERE phase_id='phase-cancelled'", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT ended_at FROM task_phases WHERE phase_id='phase-cancelled'", java.sql.Timestamp.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT result_json FROM task_phases WHERE phase_id='phase-cancelled'", String.class))
                .isNull();
    }

    @Test
    void terminalPhaseWithResultFactIsNeverReactivated() {
        instantiateTask("task-terminal", "phase-terminal");
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,result_json,failure_reason,ended_at,create_time,update_time) "
                        + "VALUES (?,?,?,5,'COMPLETED',?,?,?,?,NULL,?,NOW(),NOW())",
                "phase-terminal", "task-terminal", "office",
                java.sql.Timestamp.from(Instant.parse("2030-01-01T10:00:00Z")),
                "{\"phaseId\":\"phase-terminal\",\"at\":\"2030-01-01T10:00:00Z\",\"actions\":[]}",
                "{\"phaseId\":\"phase-terminal\",\"at\":\"2030-01-01T10:00:00Z\",\"actions\":[]}",
                "{\"outcome\":\"COMPLETED\"}",
                java.sql.Timestamp.from(Instant.parse("2030-01-01T10:05:00Z")));

        scheduler.schedule("task-terminal", "phase-terminal", Instant.parse("2030-01-01T11:00:00Z"));

        assertThat(status("phase-terminal")).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT version FROM task_phases WHERE phase_id='phase-terminal'", Long.class))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT result_json FROM task_phases WHERE phase_id='phase-terminal'", String.class))
                .contains("COMPLETED");
    }

    private void instantiateTask(String taskId, String phaseId) {
        String payload = mapper.valueToTree(Map.of(
                "source", "office",
                "tenantCode", "LOCAL",
                "ownerKey", "owner-1",
                "phases", java.util.List.of(Map.of(
                        "phaseId", phaseId,
                        "at", "2030-01-01T11:00:00Z",
                        "actions", java.util.List.of())))).toString();
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                        + "VALUES (?,1,'SCHEDULED',?,?,NOW(),NOW())",
                taskId, payload, taskId + "-key");
    }

    private String status(String phaseId) {
        return jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id=?", String.class, phaseId);
    }
}
