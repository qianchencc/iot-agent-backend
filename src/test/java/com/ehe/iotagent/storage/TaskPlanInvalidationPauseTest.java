package com.ehe.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import com.ehe.iotagent.task.TaskPlanFact;
import com.ehe.iotagent.task.TaskPlanStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan invalidation and pause/resume durability: an authorization failure
 * pauses only future materialization with the Runtime annotation, manual
 * transitions maintain pause_reason, resume re-derives a future-only pointer,
 * and tenant-wide listing stays owner-agnostic for the read model.
 */
class TaskPlanInvalidationPauseTest {
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TaskPlanStore plans;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:plan_pause_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactionsAndStores(source);
    }

    private void transactionsAndStores(JdbcDataSource source) {
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_plans (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, next_at TIMESTAMP NULL, next_date DATE NULL, repeat_json VARCHAR(2000) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, pause_reason VARCHAR(1024) NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(tenant_code, owner_key, idempotency_key))");
        jdbc.execute("CREATE TABLE task_plan_occurrences (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, occurrence_date DATE NOT NULL, task_id VARCHAR(64) NULL, status VARCHAR(32) NOT NULL, override_payload_json VARCHAR(20000) NULL, version BIGINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(plan_id, occurrence_date))");
        plans = TaskFactStoresFactory.taskPlans(jdbc, transactions, mapper);
    }


    @Test
    void invalidationPausesEnabledPlanWithAnnotationAndKeepsOccurrenceHistory() {
        insertEnabledPlan("plan-invalidate", "TENANT", "owner-1");
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,version,create_time,update_time) "
                        + "VALUES ('plan-invalidate','2026-11-01','task-history','MATERIALIZED',1,?,?)",
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        TaskPlanFact paused = plans.pauseForInvalidation("plan-invalidate", "公司授权失效", NOW).orElseThrow();

        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.version()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT pause_reason FROM task_plans WHERE plan_id='plan-invalidate'", String.class))
                .isEqualTo("自动停用：公司授权失效");
        assertThat(jdbc.queryForObject("SELECT next_at FROM task_plans WHERE plan_id='plan-invalidate'", java.sql.Timestamp.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id='plan-invalidate'", String.class))
                .isEqualTo("MATERIALIZED");
        assertThat(plans.pauseReason("plan-invalidate")).contains("自动停用：公司授权失效");

        // Already paused: returns the current fact without another version bump.
        TaskPlanFact current = plans.pauseForInvalidation("plan-invalidate", "重复失效", NOW).orElseThrow();
        assertThat(current.version()).isEqualTo(2L);
        assertThat(plans.pauseForInvalidation("plan-missing", "不存在", NOW)).isEmpty();
    }

    @Test
    void manualPauseAndResumeMaintainPauseReasonAndFutureOnlyPointer() {
        insertEnabledPlan("plan-manual", "TENANT", "owner-2");
        long before = jdbc.queryForObject("SELECT version FROM task_plans WHERE plan_id='plan-manual'", Long.class);

        TaskPlanFact paused = plans.updateIfVersion(new TaskPlanFact("plan-manual", before + 1, "PAUSED",
                payload("plan-manual", "owner-2"), "idem-plan-manual"), before).orElseThrow();
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(plans.pauseReason("plan-manual")).contains("用户手动停用");
        assertThat(plans.pointer("plan-manual")).map(TaskPlanStore.PlanPointer::nextAt).isEmpty();

        long pausedVersion = jdbc.queryForObject("SELECT version FROM task_plans WHERE plan_id='plan-manual'", Long.class);
        TaskPlanFact resumed = plans.updateIfVersion(new TaskPlanFact("plan-manual", pausedVersion + 1, "ENABLED",
                payload("plan-manual", "owner-2"), "idem-plan-manual"), pausedVersion).orElseThrow();
        assertThat(resumed.status()).isEqualTo("ENABLED");
        assertThat(plans.pauseReason("plan-manual")).isEmpty();
        TaskPlanStore.PlanPointer pointer = plans.pointer("plan-manual").orElseThrow();
        assertThat(pointer.nextAt()).isAfter(NOW);
        assertThat(pointer.nextDate()).isAfter(LocalDate.parse("2026-11-01"));
        assertThat(pointer.updatedAt()).isNotNull();
    }

    @Test
    void tenantListingIsOwnerAgnosticAndNewestFirst() {
        insertEnabledPlan("plan-a", "TENANT", "owner-1");
        insertEnabledPlan("plan-b", "TENANT", "owner-2", 5);
        jdbc.update("INSERT INTO task_plans(plan_id,tenant_code,owner_key,source,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,pause_reason,create_time,update_time) "
                        + "VALUES ('plan-c','TENANT','owner-1','company',1,'PAUSED',NULL,NULL,'{\"frequency\":\"daily\"}',?,'idem-plan-c',NULL,?,?)",
                payload("plan-c", "owner-1"), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(10)));

        assertThat(plans.listByTenant("TENANT", "company"))
                .extracting(TaskPlanFact::planId)
                .containsExactly("plan-c", "plan-b", "plan-a");
        assertThat(plans.list("TENANT", "owner-1", "company"))
                .extracting(TaskPlanFact::planId)
                .containsExactlyInAnyOrder("plan-a", "plan-c");
    }

    private void insertEnabledPlan(String planId, String tenant, String owner) {
        insertEnabledPlan(planId, tenant, owner, 0);
    }
    private void insertEnabledPlan(String planId, String tenant, String owner, int updateSecondsLater) {
        jdbc.update("INSERT INTO task_plans(plan_id,tenant_code,owner_key,source,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,pause_reason,create_time,update_time) "
                        + "VALUES (?,?,?,'company',1,'ENABLED',?,'2026-11-02','{\"frequency\":\"daily\"}',?,?,NULL,?,?)",
                planId, tenant, owner, java.sql.Timestamp.from(Instant.parse("2026-11-02T09:00:00Z")),
                payload(planId, owner), "idem-" + planId,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(updateSecondsLater)));
    }

    private String payload(String planId, String owner) {
        return "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner + "\",\"label\":\"" + planId
                + "\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"phases\":[{\"at\":\"2026-11-02T09:00:00Z\",\"actions\":[]}]}";
    }
}
