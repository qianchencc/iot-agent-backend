package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import org.h2.jdbcx.JdbcDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskOccurrenceMaterializer;
class JdbcTaskRecurrenceMaterializerTest {
    private static final Instant NOW = Instant.parse("2026-11-01T01:00:01Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskExecutionIdentityStore identities = new TaskExecutionIdentityStore() {
        @Override public void saveForTask(String taskId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) { }
        @Override public void saveForPlan(String planId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) { }
        @Override public java.util.Optional<TaskExecutionIdentityStore.TaskExecutionIdentity> loadForTask(String taskId) {
            return java.util.Optional.empty();
        }
        @Override public void copyPlanIdentityToTask(String planId, String taskId) { }
    };
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recurrence_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSource source = dataSource;
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_plans (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, next_at TIMESTAMP NULL, next_date DATE NULL, repeat_json VARCHAR(2000) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_plan_occurrences (id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, occurrence_date DATE NOT NULL, task_id VARCHAR(64) NULL, status VARCHAR(32) NOT NULL, override_payload_json VARCHAR(20000) NULL, version BIGINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL, UNIQUE(plan_id, occurrence_date))");
        jdbc.execute("CREATE TABLE task_facts (id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, payload_json VARCHAR(20000) NOT NULL, idempotency_key VARCHAR(256) NOT NULL UNIQUE, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE task_phases (id BIGINT AUTO_INCREMENT PRIMARY KEY, phase_id VARCHAR(64) NOT NULL UNIQUE, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, version BIGINT NOT NULL, status VARCHAR(32) NOT NULL, due_at TIMESTAMP NOT NULL, payload_json VARCHAR(20000) NOT NULL, definition_json VARCHAR(20000) NULL, result_json VARCHAR(20000) NULL, failure_reason VARCHAR(1024) NULL, claimed_at TIMESTAMP NULL, ended_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL, update_time TIMESTAMP NOT NULL)");
    }

    @Test
    void materializesAtMostOnceAcrossConcurrentAndRestartedScans() {
        insertPlan("plan-once", "company", "owner-1", "2026-11-01T01:00:00Z");
        TaskOccurrenceMaterializer first = materializer();
        TaskOccurrenceMaterializer second = materializer();

        CompletableFuture.allOf(CompletableFuture.runAsync(first::materializeOnce), CompletableFuture.runAsync(second::materializeOnce)).join();
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(count("task_phases")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id='plan-once'", String.class)).isEqualTo("MATERIALIZED");
        assertThat(jdbc.queryForObject("SELECT next_date FROM task_plans WHERE plan_id='plan-once'", java.sql.Date.class).toLocalDate().toString()).isEqualTo("2026-11-02");

        materializer().materializeOnce();
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(count("task_phases")).isEqualTo(1);
    }

    @Test
    void skippedOccurrenceIsNeverMaterializedAndPointerAdvances() {
        insertPlan("plan-skip", "office", "owner-2", "2026-11-01T01:00:00Z");
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,version,create_time,update_time) VALUES (?,?,?,1,?,?)",
                "plan-skip", "2026-11-01", "SKIPPED", java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        assertThat(materializer().materializeOnce()).isEqualTo(1);
        assertThat(count("task_facts")).isZero();
        assertThat(jdbc.queryForObject("SELECT next_date FROM task_plans WHERE plan_id='plan-skip'", java.sql.Date.class).toLocalDate().toString()).isEqualTo("2026-11-02");
    }

    @Test
    void overridePayloadIsMaterializedWithPlanIdentityAndCredentialsRedacted() throws Exception {
        insertPlan("plan-override", "company", "stable-owner", "2026-11-01T01:00:00Z");
        String override = "{\"source\":\"office\",\"tenantCode\":\"attacker\",\"ownerKey\":\"attacker\",\"label\":\"override-label\",\"phases\":[{\"at\":\"2026-11-01T01:00:00Z\",\"actions\":[]}],\"credential\":\"secret-value\"}";
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) VALUES (?,?,?,?,1,?,?)",
                "plan-override", "2026-11-01", "OVERRIDDEN", override, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        assertThat(materializer().materializeOnce()).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload_json FROM task_facts WHERE idempotency_key='occurrence:plan-override:2026-11-01'", String.class);
        JsonNode value = mapper.readTree(payload);
        assertThat(value.path("source").asText()).isEqualTo("company");
        assertThat(value.path("tenantCode").asText()).isEqualTo("TENANT");
        assertThat(value.path("ownerKey").asText()).isEqualTo("stable-owner");
        assertThat(value.path("label").asText()).isEqualTo("override-label");
        assertThat(value.toString()).doesNotContain("secret-value");
        assertThat(jdbc.queryForObject("SELECT next_date FROM task_plans WHERE plan_id='plan-override'", java.sql.Date.class).toLocalDate().toString()).isEqualTo("2026-11-02");
    }

    @Test
    void lateRestartRecordsElapsedCheckpointsMissedAndKeepsFuturePending() throws Exception {
        String id = "plan-late-interval";
        insertIntervalPlan(id, "2026-11-01");

        Instant late = Instant.parse("2026-11-01T09:15:00Z");
        assertThat(materializer(Clock.fixed(late, ZoneOffset.UTC)).materializeOnce()).isEqualTo(1);
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(count("task_phases")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE due_at=?", String.class,
                java.sql.Timestamp.from(Instant.parse("2026-11-01T09:00:00Z")))).isEqualTo("MISSED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM task_phases WHERE due_at=?", String.class,
                java.sql.Timestamp.from(Instant.parse("2026-11-01T09:00:00Z")))).isEqualTo("服务重启时检查点已过，未下发");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING' AND due_at <= ?", Integer.class,
                java.sql.Timestamp.from(late))).isZero();
        String payload = jdbc.queryForObject("SELECT payload_json FROM task_facts WHERE idempotency_key='occurrence:" + id + ":2026-11-01'", String.class);
        assertThat(mapper.readTree(payload).path("phases").size()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id=?", String.class, id))
                .isEqualTo("MATERIALIZED");
        assertThat(nextDate(id)).isEqualTo("2026-11-02");
    }

    @Test
    void lateRestartDecidesOnShiftedOccurrenceInsteadOfRawTemplate() {
        String id = "plan-shifted-recovery";
        insertIntervalPlan(id, "2026-10-31");

        Instant late = Instant.parse("2026-11-01T09:12:00Z");
        assertThat(materializer(Clock.fixed(late, ZoneOffset.UTC)).materializeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id=?", String.class, id))
                .isEqualTo("MATERIALIZED");
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE due_at=?", String.class,
                java.sql.Timestamp.from(Instant.parse("2026-11-01T09:00:00Z")))).isEqualTo("MISSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(nextDate(id)).isEqualTo("2026-11-02");
    }

    @Test
    void lateRestartHonorsOverrideWindowWithFutureCheckpoints() {
        String id = "plan-override-recovery";
        insertIntervalPlan(id, "2026-10-31");
        String override = "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                + "\"label\":\"override-window\",\"interval\":{\"startsAt\":\"2026-11-01T09:00:00Z\",\"endsAt\":\"2026-11-01T17:00:00Z\"},"
                + "\"phases\":[{\"at\":\"2026-11-01T09:00:00Z\",\"actions\":[]},"
                + "{\"at\":\"2026-11-01T09:30:00Z\",\"actions\":[]},"
                + "{\"at\":\"2026-11-01T10:00:00Z\",\"actions\":[]}]}";
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) VALUES (?,?,?,?,1,?,?)",
                id, "2026-11-01", "OVERRIDDEN", override, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));

        Instant late = Instant.parse("2026-11-01T09:12:00Z");
        assertThat(materializer(Clock.fixed(late, ZoneOffset.UTC)).materializeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id=?", String.class, id))
                .isEqualTo("MATERIALIZED");
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases WHERE due_at=?", String.class,
                java.sql.Timestamp.from(Instant.parse("2026-11-01T09:00:00Z")))).isEqualTo("MISSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(nextDate(id)).isEqualTo("2026-11-02");
    }

    @Test
    void onTimeMaterializationKeepsDueStartPhasePending() {
        String id = "plan-ontime-start";
        insertIntervalPlan(id, "2026-11-01");

        Instant justAfterStart = Instant.parse("2026-11-01T09:00:05Z");
        assertThat(materializer(Clock.fixed(justAfterStart, ZoneOffset.UTC)).materializeOnce()).isEqualTo(1);
        assertThat(count("task_facts")).isEqualTo(1);
        assertThat(count("task_phases")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status<>'PENDING'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE due_at <= ?", Integer.class,
                java.sql.Timestamp.from(justAfterStart))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id=?", String.class, id))
                .isEqualTo("MATERIALIZED");
        assertThat(nextDate(id)).isEqualTo("2026-11-02");
    }

    @Test
    void elapsedOccurrenceRetainsMissedPhaseHistoryWithoutDispatchingItAgain() {
        insertPlan("plan-missed", "office", "fixture-owner", "2026-11-01T08:00:00Z");
        var recovered = materializer(Clock.fixed(Instant.parse("2026-11-01T09:00:00Z"), ZoneOffset.UTC));
        assertThat(recovered.materializeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases", String.class)).isEqualTo("MISSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE status='PENDING'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM task_plan_occurrences WHERE plan_id='plan-missed'", String.class))
                .isEqualTo("MATERIALIZED");
        assertThat(nextDate("plan-missed")).isEqualTo("2026-11-02");
        recovered.materializeOnce();
        assertThat(count("task_facts")).isEqualTo(1);
    }

    @Test
    void changedOccurrenceUsesItsEffectiveTimeForTheLateExecutionGrace() {
        insertPlan("plan-effective-time", "company", "fixture-owner", "2026-11-01T02:26:00Z");
        String override = "{\"source\":\"company\",\"phases\":[{\"at\":\"2026-11-01T13:30:00+08:00\",\"actions\":[]}]}";
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) VALUES (?,?,?,?,1,?,?)",
                "plan-effective-time", "2026-11-01", "OVERRIDDEN", override, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        assertThat(materializer(Clock.fixed(Instant.parse("2026-11-01T05:32:52Z"), ZoneOffset.UTC)).materializeOnce()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_phases", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT due_at FROM task_phases", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        assertThat(nextDate("plan-effective-time")).isEqualTo("2026-11-02");
    }

    @Test
    void nextPointerKeepsTheFollowingDatesSingleOccurrenceOverrideTime() {
        insertPlan("plan-next-override", "company", "fixture-owner", "2026-11-01T01:00:00Z");
        String override = "{\"source\":\"company\",\"phases\":[{\"at\":\"2026-11-02T05:30:00Z\",\"actions\":[]}]}";
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) VALUES (?,?,?,?,1,?,?)",
                "plan-next-override", "2026-11-02", "OVERRIDDEN", override, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        materializer().materializeOnce();
        assertThat(jdbc.queryForObject("SELECT next_at FROM task_plans WHERE plan_id='plan-next-override'", java.sql.Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-11-02T05:30:00Z"));
    }

    @Test
    void previouslyAbandonedOverrideGetsMissedHistoryWithoutRewindingTheFuturePointer() {
        String id = "plan-orphaned-override";
        insertPlan(id, "company", "fixture-owner", "2026-11-01T02:26:00Z");
        jdbc.update("UPDATE task_plans SET next_at=?,next_date='2026-11-02' WHERE plan_id=?",
                java.sql.Timestamp.from(Instant.parse("2026-11-02T02:26:00Z")), id);
        String override = "{\"source\":\"company\",\"phases\":[{\"at\":\"2026-11-01T05:30:00Z\",\"actions\":[]}]}";
        jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) VALUES (?,?,?,?,1,?,?)",
                id, "2026-11-01", "OVERRIDDEN", override, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
        var recovered = materializer(Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC));
        recovered.materializeOnce();
        assertThat(jdbc.queryForList("SELECT status FROM task_phases", String.class)).containsExactly("MISSED");
        assertThat(nextDate(id)).isEqualTo("2026-11-02");
        recovered.materializeOnce();
        assertThat(count("task_facts")).isEqualTo(1);
    }

    private TaskOccurrenceMaterializer materializer() {
        return materializer(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TaskOccurrenceMaterializer materializer(Clock clock) {
        return TaskOccurrenceMaterializerFactory.jdbc(jdbc, transactions, mapper, clock, 10, identities);
    }

    private void insertPlan(String id, String source, String owner, String at) {
        String payload = "{\"source\":\"" + source + "\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner
                + "\",\"label\":\"recurring\",\"repeat\":{\"frequency\":\"daily\"},\"phases\":[{\"at\":\"" + at + "\",\"actions\":[]}],\"credential\":\"secret-value\"}";
        jdbc.update("INSERT INTO task_plans(plan_id,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, 1, "ENABLED", java.sql.Timestamp.from(Instant.parse(at)), "2026-11-01", "{\"frequency\":\"daily\"}", payload, id,
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private void insertIntervalPlan(String id, String anchorDate) {
        String payload = "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                + "\"label\":\"interval\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"interval\":{\"startsAt\":\"" + anchorDate + "T09:00:00Z\",\"endsAt\":\"" + anchorDate + "T17:00:00Z\"},"
                + "\"phases\":[{\"at\":\"" + anchorDate + "T09:00:00Z\",\"actions\":[]},"
                + "{\"at\":\"" + anchorDate + "T09:30:00Z\",\"actions\":[]},"
                + "{\"at\":\"" + anchorDate + "T10:00:00Z\",\"actions\":[]}]}";
        jdbc.update("INSERT INTO task_plans(plan_id,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, 1, "ENABLED", java.sql.Timestamp.from(Instant.parse("2026-11-01T09:00:00Z")), "2026-11-01",
                "{\"frequency\":\"daily\"}", payload, id, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    private String nextDate(String id) {
        return jdbc.queryForObject("SELECT next_date FROM task_plans WHERE plan_id=?", java.sql.Date.class, id).toLocalDate().toString();
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
}
