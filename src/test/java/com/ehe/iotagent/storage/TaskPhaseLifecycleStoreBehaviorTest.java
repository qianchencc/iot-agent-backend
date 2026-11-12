package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import com.ehe.iotagent.task.TaskFact;
import com.ehe.iotagent.task.TaskFactStore;
import com.ehe.iotagent.task.TaskPhaseStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class TaskPhaseLifecycleStoreBehaviorTest {
    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private TaskFactStore tasks;
    private TaskPhaseStore phases;
    private com.ehe.iotagent.task.TaskAtomicTransactions atomic;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:phase_lifecycle_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        ObjectMapper mapper = new ObjectMapper();
        tasks = TaskFactStoresFactory.taskFacts(jdbc, transactions, mapper);
        phases = TaskFactStoresFactory.taskPhases(jdbc, transactions, mapper);
        atomic = TaskAtomicSupportFactory.atomicTransactions(transactions);
        tasks.save(new TaskFact("task", 1, "SCHEDULED", "{\"source\":\"office\",\"phases\":[{\"phaseId\":\"phase\",\"at\":\"" + NOW + "\",\"actions\":[]}]}", "task"));
        TaskSchedulerFactory.jdbc(jdbc, mapper).schedule("task", "phase", NOW);
    }

    @Test
    void claimedPhaseAndParentProgressAreVisibleTogether() {
        TaskFact before = tasks.find("task").orElseThrow();
        assertThat(phases.claim("phase", 1, NOW)).isTrue();
        assertThat(phases.find("phase").orElseThrow().status()).isEqualTo("EXECUTING");
        TaskFact running = tasks.find("task").orElseThrow();
        assertThat(running.status()).isEqualTo("EXECUTING");
        assertThat(running.version()).isGreaterThan(before.version());
        assertThat(phases.finish("phase", 2, "COMPLETED", "{}", NOW.plusSeconds(1))).isTrue();
        assertThat(tasks.find("task").orElseThrow().version()).isGreaterThan(running.version());
    }

    @Test
    void failedClaimTransactionCannotLeaveAnExecutingPhaseOrParent() {
        assertThatThrownBy(() -> atomic.write(() -> {
            phases.claim("phase", 1, NOW);
            throw new IllegalStateException("interrupted write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(phases.find("phase").orElseThrow().status()).isEqualTo("PENDING");
        assertThat(tasks.find("task").orElseThrow().status()).isEqualTo("SCHEDULED");
    }

    @Test
    void cancelledTaskCannotBeClaimedAndRejectedClaimDoesNotAdvanceVersion() {
        TaskFact current = tasks.find("task").orElseThrow();
        TaskFact cancelled = new TaskFact("task", current.version() + 1, "CANCELLED", current.payloadJson(), current.idempotencyKey());
        tasks.updateIfVersion(cancelled, current.version());
        assertThat(phases.claim("phase", 1, NOW)).isFalse();
        assertThat(phases.find("phase").orElseThrow().status()).isEqualTo("PENDING");
        assertThat(tasks.find("task").orElseThrow()).isEqualTo(cancelled);
    }
}
