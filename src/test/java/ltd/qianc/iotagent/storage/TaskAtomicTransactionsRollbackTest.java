package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import ltd.qianc.iotagent.task.TaskAtomicTransactions;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consumer-visible atomicity of the task transaction boundary: proposal CAS,
 * task fact creation and phase scheduling commit together or not at all, so a
 * crash between them replays as the original pending confirmation instead of a
 * VERSION_CONFLICT against a half-applied change.
 */
class TaskAtomicTransactionsRollbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TaskAtomicTransactions atomic;
    private TaskProposalStore proposals;
    private TaskFactStore tasks;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task_atomic_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_proposals (id BIGINT AUTO_INCREMENT PRIMARY KEY, proposal_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, turn_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, kind VARCHAR(32) NOT NULL DEFAULT 'TASK', target_json VARCHAR(20000) NULL, before_json VARCHAR(20000) NULL, after_json VARCHAR(20000) NULL, operation VARCHAR(32) NOT NULL DEFAULT 'create', source VARCHAR(16) NOT NULL DEFAULT 'office', expires_at DATETIME NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(tenant_code, owner_key, idempotency_key))");
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        atomic = TaskAtomicSupportFactory.atomicTransactions(transactions);
        proposals = TaskFactStoresFactory.taskProposals(jdbc, transactions, mapper);
        tasks = TaskFactStoresFactory.taskFacts(jdbc, transactions, mapper);
        scheduler = TaskSchedulerFactory.jdbc(jdbc, mapper);
    }

    @Test
    void failureInsideTheBoundaryRollsBackProposalCasFactAndPhasesTogether() {
        TaskProposalFact proposal = proposals.save(new TaskProposalFact("proposal-rollback", 1,
                "PENDING_CONFIRMATION", confirmedPayload(), "idem-rollback", "TASK", "{}", "{}",
                confirmedPayload(), "create", "TENANT", "owner-1", "office",
                Instant.parse("2030-01-01T00:00:00Z")));

        // The facade's shape: CAS the proposal, materialize the fact, schedule
        // phases — one crashing step must leave the confirmation replayable.
        assertThatThrownBy(() -> atomic.write(() -> {
            proposals.updateIfVersion(withStatus(proposal, "CONFIRMED"), proposal.version())
                    .orElseThrow(() -> new IllegalStateException("proposal CAS lost"));
            TaskFact task = tasks.save(new TaskFact("proposal-rollback", 1, "SCHEDULED",
                    confirmedPayload(), "task:proposal-rollback"));
            scheduler.schedule(task.taskId(), "phase-a", Instant.parse("2030-01-01T09:00:00Z"));
            throw new IllegalStateException("crash between confirmation steps");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("crash between");

        assertThat(jdbc.queryForObject("SELECT status FROM task_proposals WHERE proposal_id='proposal-rollback'", String.class))
                .isEqualTo("PENDING_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases", Integer.class)).isZero();

        // The replay after the failed attempt still sees version 1 pending and
        // completes without any VERSION_CONFLICT.
        TaskFact replayed = atomic.write(() -> {
            TaskProposalFact confirmed = proposals.updateIfVersion(withStatus(proposal, "CONFIRMED"), proposal.version())
                    .orElseThrow(() -> new IllegalStateException("proposal CAS lost"));
            TaskFact task = tasks.findByIdempotencyKey("task:proposal-rollback").orElseGet(() ->
                    tasks.save(new TaskFact("proposal-rollback", 1, "SCHEDULED", confirmed.payloadJson(), "task:proposal-rollback")));
            scheduler.schedule(task.taskId(), "phase-a", Instant.parse("2030-01-01T09:00:00Z"));
            return task;
        });
        assertThat(replayed.taskId()).isEqualTo("proposal-rollback");
        assertThat(jdbc.queryForObject("SELECT status FROM task_proposals WHERE proposal_id='proposal-rollback'", String.class))
                .isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id='phase-a'", String.class))
                .isEqualTo("PENDING");
    }

    @Test
    void committedBoundaryKeepsProposalFactAndPhasesVisibleTogether() {
        TaskProposalFact proposal = proposals.save(new TaskProposalFact("proposal-commit", 1,
                "PENDING_CONFIRMATION", confirmedPayload(), "idem-commit", "TASK", "{}", "{}",
                confirmedPayload(), "create", "TENANT", "owner-1", "office",
                Instant.parse("2030-01-01T00:00:00Z")));

        atomic.write(() -> {
            proposals.updateIfVersion(withStatus(proposal, "CONFIRMED"), proposal.version());
            TaskFact task = tasks.save(new TaskFact("proposal-commit", 1, "SCHEDULED",
                    confirmedPayload(), "task:proposal-commit"));
            scheduler.schedule(task.taskId(), "phase-a", Instant.parse("2030-01-01T09:00:00Z"));
            return null;
        });

        assertThat(tasks.findByIdempotencyKey("task:proposal-commit")).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id='proposal-commit'", Integer.class))
                .isEqualTo(1);
    }
    private String confirmedPayload() {
        return "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                + "\"conversationId\":\"conversation-1\",\"turnId\":\"turn-1\",\"label\":\"evening off\","
                + "\"phases\":[{\"phaseId\":\"phase-a\",\"at\":\"2030-01-01T09:00:00Z\",\"actions\":[]}]}";
    }

    private TaskProposalFact withStatus(TaskProposalFact proposal, String status) {
        return new TaskProposalFact(proposal.proposalId(), proposal.version() + 1, status,
                proposal.payloadJson(), proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(),
                proposal.beforeJson(), proposal.afterJson(), proposal.operation(), proposal.tenantCode(),
                proposal.ownerKey(), proposal.source(), proposal.expiresAt());
    }
}
