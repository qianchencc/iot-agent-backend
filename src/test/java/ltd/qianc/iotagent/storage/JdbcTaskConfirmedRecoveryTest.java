package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Confirmed-but-unmaterialized proposals are补登记 by the recovery sweep after
 * an interrupted confirm: facts appear by the facade's idempotency keys,
 * missing phase rows are scheduled once, terminal history is never rewritten
 * and reruns never duplicate tasks or phases.
 */
class JdbcTaskConfirmedRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TaskConfirmedRecovery recovery;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:task_recovery_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_proposals (id BIGINT AUTO_INCREMENT PRIMARY KEY, proposal_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, turn_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, kind VARCHAR(32) NOT NULL DEFAULT 'TASK', target_json VARCHAR(20000) NULL, before_json VARCHAR(20000) NULL, after_json VARCHAR(20000) NULL, operation VARCHAR(32) NOT NULL DEFAULT 'create', source VARCHAR(16) NOT NULL DEFAULT 'office', expires_at DATETIME NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(tenant_code, owner_key, idempotency_key))");
        jdbc.execute("CREATE TABLE task_plans (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, next_at TIMESTAMP NULL, next_date DATE NULL, repeat_json VARCHAR(2000) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, pause_reason VARCHAR(1024) NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(tenant_code, owner_key, idempotency_key))");
        jdbc.execute("CREATE TABLE task_plan_occurrences (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, occurrence_date DATE NOT NULL, task_id VARCHAR(64) NULL, status VARCHAR(32) NOT NULL, override_payload_json VARCHAR(20000) NULL, version BIGINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(plan_id, occurrence_date))");
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        TaskPlanStore plans = TaskFactStoresFactory.taskPlans(jdbc, transactions, mapper);
        TaskFactStore tasks = TaskFactStoresFactory.taskFacts(jdbc, transactions, mapper);
        TaskScheduler scheduler = TaskSchedulerFactory.jdbc(jdbc, mapper);
        recovery = TaskAtomicSupportFactory.confirmedRecovery(jdbc, transactions, plans, tasks, scheduler, mapper);
    }

    @Test
    void interruptedOnceConfirmationIsMaterializedWithPhaseIdsAndIsIdempotent() throws Exception {
        insertProposal("proposal-once", "CONFIRMED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]},"
                        + "{\"at\":\"2026-11-03T09:30:00Z\",\"actions\":[]}]}");

        TaskConfirmedRecovery.RecoveryResult first = recovery.recoverOnce();

        assertThat(first.recovered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT task_id FROM task_facts WHERE idempotency_key='task:proposal-once'", String.class))
                .isEqualTo("proposal-once");
        String payload = jdbc.queryForObject("SELECT payload_json FROM task_facts WHERE task_id='proposal-once'", String.class);
        JsonNode phases = mapper.readTree(payload).path("phases");
        assertThat(phases.get(0).path("phaseId").asText()).isNotBlank();
        assertThat(phases.get(1).path("phaseId").asText()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id='proposal-once'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING'", Integer.class))
                .isEqualTo(2);

        TaskConfirmedRecovery.RecoveryResult second = recovery.recoverOnce();
        assertThat(second.recovered()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts WHERE idempotency_key='task:proposal-once'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id='proposal-once'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void existingFactWithMissingPhaseRowOnlySchedulesTheGapAndKeepsHistory() {
        insertProposal("proposal-gap", "CONFIRMED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"phaseId\":\"gap-kept\",\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]},"
                        + "{\"phaseId\":\"gap-missing\",\"at\":\"2026-11-03T09:30:00Z\",\"actions\":[]}]}");
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                        + "VALUES ('legacy-random-id',1,'SCHEDULED',?,?,NOW(),NOW())",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"phaseId\":\"gap-kept\",\"at\":\"2026-11-03T09:00:00Z\",\"status\":\"COMPLETED\",\"actions\":[]},"
                        + "{\"phaseId\":\"gap-missing\",\"at\":\"2026-11-03T09:30:00Z\",\"actions\":[]}]}",
                "task:proposal-gap");
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,result_json,ended_at,create_time,update_time) "
                        + "VALUES ('gap-kept','legacy-random-id','office',2,'COMPLETED',?,?,?,?,?,NOW(),NOW())",
                java.sql.Timestamp.from(Instant.parse("2026-11-03T09:00:00Z")),
                "{\"phaseId\":\"gap-kept\",\"at\":\"2026-11-03T09:00:00Z\",\"status\":\"COMPLETED\",\"actions\":[]}",
                "{\"phaseId\":\"gap-kept\",\"at\":\"2026-11-03T09:00:00Z\",\"status\":\"COMPLETED\",\"actions\":[]}",
                "{\"outcome\":\"COMPLETED\"}",
                java.sql.Timestamp.from(Instant.parse("2026-11-03T09:05:00Z")));

        TaskConfirmedRecovery.RecoveryResult result = recovery.recoverOnce();

        assertThat(result.recovered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id='gap-kept'", String.class))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE phase_id='gap-missing'", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT task_id FROM task_phases WHERE phase_id='gap-missing'", String.class))
                .isEqualTo("legacy-random-id");
    }

    @Test
    void interruptedPlanConfirmationRegistersEnabledPlanByIdempotencyKey() {
        insertProposal("proposal-plan", "CONFIRMED",
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\",\"label\":\"daily off\","
                        + "\"repeat\":{\"frequency\":\"daily\"},"
                        + "\"phases\":[{\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]}]}");

        TaskConfirmedRecovery.RecoveryResult first = recovery.recoverOnce();

        assertThat(first.recovered()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plans WHERE idempotency_key='plan:proposal-plan'", String.class))
                .isEqualTo("ENABLED");
        assertThat(jdbc.queryForObject("SELECT plan_id FROM task_plans WHERE idempotency_key='plan:proposal-plan'", String.class))
                .isEqualTo("proposal-plan");
        assertThat(jdbc.queryForObject("SELECT next_date FROM task_plans WHERE plan_id='proposal-plan'", java.sql.Date.class))
                .isNotNull();

        assertThat(recovery.recoverOnce().recovered()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_plans", Integer.class)).isEqualTo(1);
    }

    @Test
    void pendingProposalsAndTerminalTasksAndUnrecoverablePayloadsStayUntouched() {
        insertProposal("proposal-pending", "PENDING_CONFIRMATION",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]}]}");
        insertProposal("proposal-cancelled-fact", "CONFIRMED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"phaseId\":\"never-again\",\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]}]}");
        jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) "
                        + "VALUES ('proposal-cancelled-fact',1,'CANCELLED',?,?,NOW(),NOW())",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"phases\":[{\"phaseId\":\"never-again\",\"at\":\"2026-11-03T09:00:00Z\",\"actions\":[]}]}",
                "task:proposal-cancelled-fact");
        insertProposal("proposal-broken", "CONFIRMED", "{\"source\":\"office\",\"label\":\"no phases\"}");

        TaskConfirmedRecovery.RecoveryResult result = recovery.recoverOnce();

        assertThat(result.recovered()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts WHERE idempotency_key='task:proposal-pending'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE phase_id='never-again'", Integer.class))
                .isZero();
    }

    private void insertProposal(String proposalId, String status, String payload) {
        jdbc.update("INSERT INTO task_proposals(proposal_id,tenant_code,owner_key,conversation_id,turn_id,version,status,"
                        + "payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,source,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,1,?,?,'idem-" + proposalId + "','TASK','{}','{}','{}','create','office',NOW(),NOW())",
                proposalId, "TENANT", "owner-1", "conversation-1", "turn-1", status, payload);
    }
}
