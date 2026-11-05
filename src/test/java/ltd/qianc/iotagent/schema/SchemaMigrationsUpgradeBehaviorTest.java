package ltd.qianc.iotagent.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 旧库升级行为：数据保留、自然唯一键语义保留、重复升级幂等、
 * 历史重复句柄安全失败。H2（MODE=MySQL）执行与生产相同的迁移代码路径，
 * MySQL 专有收敛（列注释、TIMESTAMP 转 DATETIME、TINYINT UNSIGNED）由父级在真实 MySQL 上验证。
 */
class SchemaMigrationsUpgradeBehaviorTest {

    @Test
    void upgradesLegacyShapesPreservingDataAndRepeatsIdempotently() {
        JdbcTemplate jdbc = legacyDatabase();
        DataSource source = jdbc.getDataSource();
        SchemaMigrations migrations = new SchemaMigrations(source);
        migrations.ensureSharedFacts();
        migrations.ensureTaskLifecycle();
        migrations.ensureDeviceCandidateSets();
        migrations.ensurePiSessions();
        long registryRowsAfterFirstRun = registryRows(jdbc);
        // 重复执行（模拟重复启动）不抛错、不重复迁移、不产生新的登记副作用。
        migrations.ensureSharedFacts();
        migrations.ensureTaskLifecycle();
        migrations.ensureDeviceCandidateSets();
        migrations.ensurePiSessions();
        assertThat(registryRows(jdbc)).isEqualTo(registryRowsAfterFirstRun);

        // 迁移登记表：旧行保留原 applied_at；审计时间回填自 applied_at 而非迁移时刻。
        var registry = jdbc.queryForMap(
                "SELECT applied_at, create_time FROM storage_migrations WHERE migration_key='legacy-row-1'");
        assertThat(String.valueOf(registry.get("APPLIED_AT"))).startsWith("2026-01-02 03:04:05.678");
        assertThat(String.valueOf(registry.get("CREATE_TIME"))).startsWith("2026-01-02 03:04:05.678");

        // Pi 四表：数据保留、代理主键 id、自然唯一键仍拒绝重复提交。
        assertThat(jdbc.<String>queryForObject(
                "SELECT epoch, version, lease_until FROM pi_session_scopes WHERE scope_key='scope-1'", (rs, n) ->
                        rs.getLong(1) + "/" + rs.getLong(2) + "/" + rs.getLong(3))).isEqualTo("3/7/1000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pi_session_chunks WHERE scope_key='scope-1' AND document_key='doc-1'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT response_json FROM pi_session_commits WHERE commit_id='commit-1'", String.class))
                .contains("\"epoch\":3");
        assertThat(primaryKeyOf(jdbc, "pi_session_commits")).containsExactly("ID");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO pi_session_commits(scope_key,commit_id,request_hash,response_json) "
                        + "VALUES('scope-1','commit-1','other-hash','{}')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // 读快照两表：数据保留、id 主键、检查点 create_time 回填自 update_time（保留原值）。
        assertThat(jdbc.queryForObject(
                "SELECT create_time FROM read_snapshot_recovery_checkpoints WHERE gateway_namespace='gw'",
                java.sql.Timestamp.class).toString()).startsWith("2026-02-03 04:05:06.789");
        assertThat(primaryKeyOf(jdbc, "read_snapshot_credential_barriers")).containsExactly("ID");
        assertThat(jdbc.queryForObject(
                "SELECT generation FROM read_snapshot_credential_barriers WHERE credential_hash=?",
                String.class, "h".repeat(64))).isEqualTo("gen-1");

        // 任务四表：数据保留、自然唯一键幂等语义保留。
        assertThat(jdbc.queryForObject(
                "SELECT payload FROM task_execution_identities WHERE owner_id='task-1'", String.class))
                .isEqualTo("cipher-text");
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM task_report_attempts WHERE task_id='task-1'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_device_dispatch_reservations WHERE task_id='task-1'", Long.class))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO task_device_dispatch_reservations"
                        + "(task_id,device_id,phase_id,kind,reserved_at,create_time,update_time) "
                        + "VALUES('task-1','device-1','phase-9','attempted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // task_phases 历史缺列被防御补齐，definition_json 回填自同行 payload_json（保留原值）。
        assertThat(jdbc.<String>queryForObject(
                "SELECT definition_json, failure_reason FROM task_phases WHERE phase_id='phase-1'", (rs, n) ->
                        rs.getString(1) + "|" + rs.getString(2)))
                .isEqualTo("{\"source\":\"office\"}|null");

        // 旧 task_plans 缺 pause_reason：注释收敛前先按基线定义防御补列（保留旧行）。
        assertThat(jdbc.queryForObject(
                "SELECT pause_reason FROM task_plans WHERE plan_id='plan-1'", String.class)).isNull();

        assertThat(jdbc.queryForObject(
                "SELECT handle FROM device_candidate_sets WHERE candidate_set_id='legacy-set-1'", String.class))
                .isEqualTo("s1");
        assertThat(jdbc.queryForObject(
                "SELECT last_handle_no FROM device_candidate_set_sequences WHERE conversation_id='conv-1'",
                Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO device_candidate_sets(candidate_set_id,conversation_id,tenant_code,owner_key,source,"
                        + "catalog_fingerprint,payload_json,complete,total_matches,created_at,handle,create_time,update_time) "
                        + "VALUES('dup-set','conv-1','tenant','" + "o".repeat(64) + "','office','"
                        + "f".repeat(64) + "','{}',1,1,'2026','s1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // 全部受影响表的代理主键统一为 id。
        for (String table : List.of("storage_migrations", "pi_session_scopes", "pi_session_documents",
                "pi_session_chunks", "pi_session_commits", "read_snapshot_credential_barriers",
                "read_snapshot_recovery_checkpoints", "task_execution_identities", "task_report_attempts",
                "task_notification_attempts", "task_device_dispatch_reservations", "device_candidate_sets")) {
            assertThat(primaryKeyOf(jdbc, table)).containsExactly("ID");
        }
    }

    @Test
    void standaloneAdministratorInitializationDoesNotRetireBusinessConversations() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:admin_schema_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE conversations (id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, "
                + "conversation_id VARCHAR(64) NOT NULL UNIQUE, tenant_code VARCHAR(128) NOT NULL, "
                + "owner_key CHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, cleared TINYINT NOT NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL)");
        jdbc.update("INSERT INTO conversations(conversation_id,tenant_code,owner_key,source,cleared,"
                + "create_time,update_time) VALUES('active','tenant',?,'office',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "o".repeat(64));

        SchemaMigrations migrations = new SchemaMigrations(source);
        migrations.ensureAdminConfiguration();
        migrations.ensureAdminConfiguration();

        assertThat(jdbc.queryForObject(
                "SELECT cleared FROM conversations WHERE conversation_id='active'", Integer.class)).isZero();
    }

    @Test
    void interruptedBarrierIssuedAtBackfillResumesOnRestart() {
        // 模拟升级中断于 ADD issued_at NULL 之后：列已存在但含 NULL，重启续跑必须回填并收紧。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:schema_barrier_resume_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE read_snapshot_credential_barriers (gateway_namespace VARCHAR(128) NOT NULL, "
                + "tenant_code VARCHAR(128) NOT NULL, credential_hash CHAR(64) NOT NULL, generation VARCHAR(64) NOT NULL, "
                + "denial_status INT NOT NULL DEFAULT 0, active_until DATETIME(3) NOT NULL, issued_at DATETIME(3) NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, "
                + "PRIMARY KEY(gateway_namespace,tenant_code,credential_hash))");
        jdbc.update("INSERT INTO read_snapshot_credential_barriers(gateway_namespace,tenant_code,credential_hash,"
                + "generation,active_until,issued_at,create_time,update_time) "
                + "VALUES('gw','tenant',?,'gen-1','2026-03-01 00:00:00',NULL,'2026-02-01 00:00:00','2026-02-05 06:07:08.123')",
                "h".repeat(64));

        new SchemaMigrations(source).ensureSharedFacts();
        // 回填来源=行内 update_time，保留原值。
        assertThat(String.valueOf(jdbc.queryForObject(
                "SELECT issued_at FROM read_snapshot_credential_barriers WHERE credential_hash=?",
                java.sql.Timestamp.class, "h".repeat(64)))).startsWith("2026-02-05 06:07:08.123");
        // 收紧后 NULL 不再被接受。
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO read_snapshot_credential_barriers(gateway_namespace,tenant_code,credential_hash,"
                        + "generation,active_until,issued_at,create_time,update_time) "
                        + "VALUES('gw','tenant',?,'gen-2','2026-04-01 00:00:00',NULL,'2026-03-01 00:00:00','2026-03-01 00:00:00')",
                "i".repeat(64))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // 重复启动不再改变已回填值。
        new SchemaMigrations(source).ensureSharedFacts();
        assertThat(String.valueOf(jdbc.queryForObject(
                "SELECT issued_at FROM read_snapshot_credential_barriers WHERE credential_hash=?",
                java.sql.Timestamp.class, "h".repeat(64)))).startsWith("2026-02-05 06:07:08.123");
    }

    @Test
    void legacyDuplicateCandidateHandlesFailSafelyWithoutRewritingHistory() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:schema_dup_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        createLegacyCandidateTable(jdbc);
        insertLegacyCandidate(jdbc, "legacy-a", "conv-dup", "s1");
        insertLegacyCandidate(jdbc, "legacy-b", "conv-dup", "s1");
        insertLegacyCandidate(jdbc, "legacy-c", "conv-other", "s1");

        assertThatThrownBy(() -> new SchemaMigrations(source).ensureDeviceCandidateSets())
                .isInstanceOf(IllegalStateException.class);
        // 失败是显式拒绝：历史行与回填结果原样保留，不被唯一键或改写处理。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_candidate_sets WHERE handle='s1'", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_candidate_sets WHERE conversation_id='conv-dup'", Long.class))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ fixtures

    /** 按各表历史形态建库并预置数据：自然/复合主键、无 id 或 id 非主键、TIMESTAMP 审计列。 */
    private static JdbcTemplate legacyDatabase() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:schema_legacy_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);

        jdbc.execute("CREATE TABLE storage_migrations (migration_key VARCHAR(128) NOT NULL, "
                + "applied_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(migration_key))");
        jdbc.update("INSERT INTO storage_migrations(migration_key, applied_at) "
                + "VALUES('legacy-row-1', '2026-01-02 03:04:05.678')");
        jdbc.update("INSERT INTO storage_migrations(migration_key) VALUES('pi-session-mysql-direct-cutover-v1')");

        jdbc.execute("CREATE TABLE pi_session_scopes (scope_key VARCHAR(64) NOT NULL, "
                + "tenant_code VARCHAR(128) NOT NULL, owner_key VARCHAR(128) NOT NULL, source VARCHAR(16) NOT NULL, "
                + "conversation_id VARCHAR(128) NOT NULL, writer_id VARCHAR(64), run_id VARCHAR(128), "
                + "server_id VARCHAR(64), binding_json LONGTEXT, epoch BIGINT NOT NULL DEFAULT 0, "
                + "version BIGINT NOT NULL DEFAULT 0, lease_until BIGINT NOT NULL DEFAULT 0, "
                + "create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(scope_key))");
        jdbc.update("INSERT INTO pi_session_scopes(scope_key,tenant_code,owner_key,source,conversation_id,"
                + "epoch,version,lease_until) VALUES('scope-1','tenant','owner','office','conv-1',3,7,1000)");

        jdbc.execute("CREATE TABLE pi_session_documents (scope_key VARCHAR(64) NOT NULL, "
                + "document_key VARCHAR(64) NOT NULL, parent_key VARCHAR(64), path_json LONGTEXT NOT NULL, "
                + "kind VARCHAR(16) NOT NULL, next_chunk BIGINT NOT NULL DEFAULT 0, "
                + "content_size BIGINT NOT NULL DEFAULT 0, modified_at BIGINT NOT NULL, "
                + "create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "PRIMARY KEY(scope_key,document_key))");
        jdbc.update("INSERT INTO pi_session_documents(scope_key,document_key,parent_key,path_json,kind,"
                + "next_chunk,content_size,modified_at) VALUES('scope-1','doc-1',NULL,'\"/sessions/history\"','file',2,20,123)");

        jdbc.execute("CREATE TABLE pi_session_chunks (scope_key VARCHAR(64) NOT NULL, "
                + "document_key VARCHAR(64) NOT NULL, chunk_no BIGINT NOT NULL, content_json LONGTEXT NOT NULL, "
                + "create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "PRIMARY KEY(scope_key,document_key,chunk_no))");
        jdbc.update("INSERT INTO pi_session_chunks(scope_key,document_key,chunk_no,content_json) "
                + "VALUES('scope-1','doc-1',0,'\"header\\n\"')");
        jdbc.update("INSERT INTO pi_session_chunks(scope_key,document_key,chunk_no,content_json) "
                + "VALUES('scope-1','doc-1',1,'\"result\\n\"')");

        jdbc.execute("CREATE TABLE pi_session_commits (scope_key VARCHAR(64) NOT NULL, "
                + "commit_id VARCHAR(64) NOT NULL, request_hash VARCHAR(64) NOT NULL, response_json LONGTEXT NOT NULL, "
                + "create_time TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), PRIMARY KEY(scope_key,commit_id))");
        jdbc.update("INSERT INTO pi_session_commits(scope_key,commit_id,request_hash,response_json) "
                + "VALUES('scope-1','commit-1','abc','{\"epoch\":3,\"version\":8}')");

        jdbc.execute("CREATE TABLE read_snapshot_credential_barriers (gateway_namespace VARCHAR(128) NOT NULL, "
                + "tenant_code VARCHAR(128) NOT NULL, credential_hash CHAR(64) NOT NULL, generation VARCHAR(64) NOT NULL, "
                + "denial_status INT NOT NULL DEFAULT 0, active_until DATETIME(3) NOT NULL, issued_at DATETIME(3) NOT NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, "
                + "PRIMARY KEY(gateway_namespace,tenant_code,credential_hash))");
        jdbc.update("INSERT INTO read_snapshot_credential_barriers(gateway_namespace,tenant_code,credential_hash,"
                + "generation,active_until,issued_at,create_time,update_time) "
                + "VALUES('gw','tenant',?,'gen-1','2026-03-01 00:00:00','2026-02-01 00:00:00','2026-02-01 00:00:00','2026-02-01 00:00:00')",
                "h".repeat(64));

        jdbc.execute("CREATE TABLE read_snapshot_recovery_checkpoints (gateway_namespace VARCHAR(128) NOT NULL, "
                + "instance_id VARCHAR(64) NOT NULL, clean TINYINT NOT NULL DEFAULT 0, recovery_epoch VARCHAR(64) NOT NULL, "
                + "update_time DATETIME(3) NOT NULL, PRIMARY KEY(gateway_namespace))");
        jdbc.update("INSERT INTO read_snapshot_recovery_checkpoints(gateway_namespace,instance_id,clean,recovery_epoch,update_time) "
                + "VALUES('gw','instance-1',1,'epoch-1','2026-02-03 04:05:06.789')");

        jdbc.execute("CREATE TABLE task_execution_identities (owner_id VARCHAR(64) NOT NULL, "
                + "owner_kind VARCHAR(16) NOT NULL, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, "
                + "payload MEDIUMTEXT NOT NULL, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, "
                + "PRIMARY KEY(owner_id))");
        jdbc.update("INSERT INTO task_execution_identities(owner_id,owner_kind,tenant_code,owner_key,payload,"
                + "create_time,update_time) VALUES('task-1','TASK','tenant',?,'cipher-text',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                "o".repeat(64));

        jdbc.execute("CREATE TABLE task_report_attempts (task_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(1024) NULL, next_attempt_at DATETIME NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, PRIMARY KEY(task_id))");
        jdbc.update("INSERT INTO task_report_attempts(task_id,status,attempts,create_time,update_time) "
                + "VALUES('task-1','FAILED',2,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        jdbc.execute("CREATE TABLE task_notification_attempts (task_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "attempts INT NOT NULL DEFAULT 0, last_error VARCHAR(1024) NULL, next_attempt_at DATETIME NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, PRIMARY KEY(task_id))");
        jdbc.update("INSERT INTO task_notification_attempts(task_id,status,attempts,create_time,update_time) "
                + "VALUES('task-1','PENDING',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        jdbc.execute("CREATE TABLE task_device_dispatch_reservations (task_id VARCHAR(64) NOT NULL, "
                + "device_id VARCHAR(64) NOT NULL, phase_id VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL, "
                + "reserved_at DATETIME NOT NULL, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, "
                + "PRIMARY KEY(task_id,device_id))");
        jdbc.update("INSERT INTO task_device_dispatch_reservations(task_id,device_id,phase_id,kind,reserved_at,create_time,update_time) "
                + "VALUES('task-1','device-1','phase-1','attempted',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO task_device_dispatch_reservations(task_id,device_id,phase_id,kind,reserved_at,create_time,update_time) "
                + "VALUES('task-1','device-2','phase-1','yielded',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        jdbc.execute("CREATE TABLE task_phases (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "phase_id VARCHAR(64) NOT NULL, task_id VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, "
                + "version BIGINT UNSIGNED NOT NULL, status VARCHAR(32) NOT NULL, due_at DATETIME NOT NULL, "
                + "payload_json TEXT NOT NULL, claimed_at DATETIME NULL, ended_at DATETIME NULL, "
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, UNIQUE KEY uk_task_phase_id (phase_id))");
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,"
                + "create_time,update_time) VALUES('phase-1','task-1','office',1,'COMPLETED',CURRENT_TIMESTAMP,"
                + "'{\"source\":\"office\"}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");

        // 旧 task_plans 无 pause_reason：注释收敛前必须先防御补列（原 TaskPlanSchemaUpgrade 语义）。
        jdbc.execute("CREATE TABLE task_plans (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "plan_id VARCHAR(64) NOT NULL, tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, "
                + "source VARCHAR(16) NOT NULL, version BIGINT UNSIGNED NOT NULL, status VARCHAR(32) NOT NULL, "
                + "next_at DATETIME NULL, next_date DATE NULL, repeat_json TEXT NOT NULL, payload_json TEXT NOT NULL, "
                + "idempotency_key VARCHAR(256) NOT NULL, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL, "
                + "UNIQUE KEY uk_task_plan_id (plan_id))");
        jdbc.update("INSERT INTO task_plans(plan_id,tenant_code,owner_key,source,version,status,repeat_json,"
                + "payload_json,idempotency_key,create_time,update_time) VALUES('plan-1','tenant',?,'office',1,"
                + "'ACTIVE','{}','{}','plan-1',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "o".repeat(64));

        createLegacyCandidateTable(jdbc);
        insertLegacyCandidate(jdbc, "legacy-set-1", "conv-1", "s1");
        insertLegacyCandidate(jdbc, "legacy-set-2", "conv-2", "s1");
        return jdbc;
    }

    private static void createLegacyCandidateTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE device_candidate_sets (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                + "candidate_set_id VARCHAR(64) NOT NULL, conversation_id VARCHAR(64) NOT NULL, "
                + "tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, "
                + "catalog_fingerprint CHAR(64) NOT NULL, payload_json TEXT NOT NULL, complete TINYINT NOT NULL, "
                + "total_matches INT NOT NULL, created_at VARCHAR(64) NOT NULL, create_time DATETIME NOT NULL, "
                + "update_time DATETIME NOT NULL, UNIQUE KEY uk_device_candidate_set_id (candidate_set_id), "
                + "KEY ix_device_candidate_conversation (conversation_id, tenant_code, owner_key, source))");
    }

    private static void insertLegacyCandidate(JdbcTemplate jdbc, String candidateSetId, String conversationId,
            String jsonHandle) {
        String payload = "{\"candidateSetId\":\"" + candidateSetId + "\",\"conversationId\":\"" + conversationId
                + "\",\"tenantCode\":\"tenant\",\"ownerKey\":\"owner\",\"source\":\"OFFICE\","
                + "\"catalogFingerprint\":\"" + "f".repeat(64) + "\",\"query\":{\"kind\":\"lighting\"},"
                + "\"members\":[],\"complete\":true,\"totalMatches\":0,\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"resolutionMode\":\"exact\",\"handle\":\"" + jsonHandle + "\"}";
        jdbc.update("INSERT INTO device_candidate_sets(candidate_set_id,conversation_id,tenant_code,owner_key,source,"
                        + "catalog_fingerprint,payload_json,complete,total_matches,created_at,create_time,update_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                candidateSetId, conversationId, "tenant", "o".repeat(64), "office", "f".repeat(64),
                payload, 1, 0, "2026-01-01T00:00:00Z");
    }

    private static long registryRows(JdbcTemplate jdbc) {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM storage_migrations", Long.class);
        return rows == null ? 0 : rows;
    }

    private static List<String> primaryKeyOf(JdbcTemplate jdbc, String table) {
        List<String> columns = new ArrayList<>();
        try (Connection connection = jdbc.getDataSource().getConnection();
                ResultSet rows = connection.getMetaData()
                        .getPrimaryKeys(connection.getCatalog(), null, table.toUpperCase())) {
            while (rows.next()) {
                columns.add(rows.getString("COLUMN_NAME").toUpperCase());
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
        return columns;
    }
}
