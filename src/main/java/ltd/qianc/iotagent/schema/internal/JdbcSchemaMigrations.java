package ltd.qianc.iotagent.schema.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 内部实现：新库按 db/mysql 资源建表，旧库按登记迁移键增量升级。
 *
 * <p>结构唯一权威是 db/mysql 的 SQL 资源：列定义与列注释从资源解析，升级 ALTER 由解析
 * 结果受控生成，避免资源 DDL 与升级常量双份漂移。所有升级步骤自守卫（先查元数据再
 * ALTER），登记键在整组步骤成功后写入，启动中断可在下次启动续跑收敛；重复启动只做
 * 登记查询，无业务副作用。列注释与 MySQL 专有类型收敛（TIMESTAMP 转 DATETIME、布尔列
 * TINYINT UNSIGNED）只在 MySQL 上执行；H2（契约测试）执行剥离注释子句的等价建表语句，
 * 结构与行为与新库一致。
 */
public final class JdbcSchemaMigrations {
    /** 会话内集合句柄：s 加十进制序号，由序号表原子分配。 */
    private static final Pattern CANDIDATE_HANDLE = Pattern.compile("^s\\d+$");
    /** 一次性会话退役迁移沿用既有登记键，保证已执行过的旧库不会重复退役。 */
    private static final String LEGACY_CUTOVER_KEY = "pi-session-mysql-direct-cutover-v1";
    /** task_phases 的任务切片防御性补列（定义权威在 agent-facts.sql 基线）。 */
    private static final String AGENT_FACTS_RESOURCE = "db/mysql/agent-facts.sql";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean mysql;

    public JdbcSchemaMigrations(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.mysql = detectMysql();
    }

    // ------------------------------------------------------------------ slices

    public void ensureSharedFacts() {
        ensureRegistry();
        runBaseline("db/mysql/agent-facts.sql", "db/mysql/agent-allowlist.sql",
                "db/mysql/read-snapshot-barrier.sql");
        ensureAdminTables();
        runOnce("read-snapshot-barrier-normalization-v2", () -> {
            normalize("read_snapshot_credential_barriers", "uk_read_snapshot_barrier_credential",
                    "gateway_namespace", "tenant_code", "credential_hash");
            ensureBarrierIssuedAtColumn();
            normalize("read_snapshot_recovery_checkpoints", "uk_read_snapshot_recovery_checkpoint",
                    "gateway_namespace");
            addColumnIfMissing("read_snapshot_recovery_checkpoints", "create_time",
                    "DATETIME(3) NULL", "recovery_epoch");
            // 回填来源=行内 update_time（该表此前无创建时间列），保留原值，不伪造迁移时刻。
            jdbc.update("UPDATE read_snapshot_recovery_checkpoints SET create_time=update_time WHERE create_time IS NULL");
            validateBooleanDomain("read_snapshot_recovery_checkpoints", "clean");
            tightenAuditColumn("db/mysql/read-snapshot-barrier.sql",
                    "read_snapshot_recovery_checkpoints", "create_time");
        });
        upgradeTaskProposalColumns();
        upgradeTaskPlanPauseReason();
        ensureTaskPhaseColumns();
        retireLegacyPiConversations();
        polish("shared-facts-column-comments-v1", "db/mysql/agent-facts.sql",
                "db/mysql/agent-allowlist.sql",
                "db/mysql/read-snapshot-barrier.sql");
    }

    /** Standalone administrator stores must not run the conversation cutover. */
    public void ensureAdminConfiguration() {
        ensureRegistry();
        ensureAdminTables();
    }

    private void ensureAdminTables() {
        runBaseline("db/mysql/admin-configuration.sql");
        polish("admin-configuration-column-comments-v1", "db/mysql/admin-configuration.sql");
    }

    public void ensureTaskLifecycle() {
        ensureRegistry();
        runBaseline("db/mysql/task-lifecycle-recovery.sql");
        ensureTaskPhaseColumns();
        runOnce("task-lifecycle-normalization-v2", () -> {
            normalize("task_execution_identities", "uk_task_execution_identity_owner", "owner_id");
            normalize("task_report_attempts", "uk_task_report_attempt_task", "task_id");
            normalize("task_notification_attempts", "uk_task_notification_attempt_task", "task_id");
            normalize("task_device_dispatch_reservations", "uk_task_dispatch_reservation",
                    "task_id", "device_id");
        });
        runOnce("task-dispatch-per-checkpoint-v1", () -> {
            addUniqueKey("task_device_dispatch_reservations", "uk_task_phase_dispatch_reservation",
                    "task_id", "phase_id", "device_id");
            if (uniqueIndexExists("task_device_dispatch_reservations", "uk_task_dispatch_reservation")) {
                jdbc.execute(mysql
                        ? "ALTER TABLE task_device_dispatch_reservations DROP INDEX uk_task_dispatch_reservation"
                        : "DROP INDEX uk_task_dispatch_reservation");
            }
        });
        polish("task-dispatch-checkpoint-comments-v1", "db/mysql/task-lifecycle-recovery.sql");
        polish("task-lifecycle-column-comments-v1", "db/mysql/task-lifecycle-recovery.sql");
    }

    public void ensureDeviceCandidateSets() {
        ensureRegistry();
        runBaseline("db/mysql/device-candidate-sets.sql");
        runOnce("device-candidate-handle-v2", () -> {
            normalize("device_candidate_sets", "uk_device_candidate_set_id", "candidate_set_id");
            addColumnIfMissing("device_candidate_sets", "handle", "VARCHAR(64) NULL", "created_at");
            backfillCandidateHandles();
            rejectDuplicateCandidateHandles();
            validateBooleanDomain("device_candidate_sets", "complete");
            addUniqueKey("device_candidate_sets", "uk_device_candidate_set_handle",
                    "conversation_id", "handle");
            initializeCandidateHandleSequences();
        });
        polish("device-candidate-set-column-comments-v2", "db/mysql/device-candidate-sets.sql");
    }

    public void ensureLocalCompanyDirectory() {
        ensureRegistry();
        runOnce("local-company-directory-v1", () -> runBaseline("db/mysql/local-company-directory.sql"));
        runOnce("local-device-catalog-v1", () -> runBaseline("db/mysql/local-device-catalog.sql"));
        runOnce("local-office-directory-v1", () -> runBaseline("db/mysql/local-office-directory.sql"));
        runOnce("local-device-states-v1", () -> runBaseline("db/mysql/local-device-states.sql"));
        runOnce("local-energy-history-v1", () -> runBaseline("db/mysql/local-energy-history.sql"));
        runOnce("local-environment-history-v1", () -> runBaseline("db/mysql/local-environment-history.sql"));
        runOnce("local-safety-history-v1", () -> runBaseline("db/mysql/local-safety-history.sql"));
        runOnce(SharedObservationMigration.KEY, () -> new SharedObservationMigration(jdbc, transactions, mapper).migrate());
        polish("local-shared-observation-column-comments-v2", "db/mysql/local-device-states.sql");
    }

    public void ensurePiSessions() {
        ensureRegistry();
        runBaseline("db/mysql/pi-sessions.sql");
        runOnce("pi-session-schema-normalization-v2", () -> {
            piTable("pi_session_scopes", "uk_pi_session_scope", "scope_key");
            piTable("pi_session_documents", "uk_pi_session_document", "scope_key", "document_key");
            piTable("pi_session_chunks", "uk_pi_session_chunk", "scope_key", "document_key", "chunk_no");
            piTable("pi_session_commits", "uk_pi_session_commit", "scope_key", "commit_id");
        });
        polish("pi-session-column-comments-v1", "db/mysql/pi-sessions.sql");
    }

    private void piTable(String table, String uniqueKey, String... naturalKey) {
        addColumnIfMissing(table, "create_time", "TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)", null);
        addColumnIfMissing(table, "update_time", "TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)", null);
        normalize(table, uniqueKey, naturalKey);
    }

    // ------------------------------------------------------------------ registry

    /** 登记表自身也按规范升级：新库直接建新结构，旧库补 id/审计列并保留 applied_at 原值。 */
    private void ensureRegistry() {
        if (!tableExists("storage_migrations")) {
            runBaseline("db/mysql/storage-migrations.sql");
        }
        runOnce("storage-migrations-registry-normalization-v2", () -> {
            addColumnIfMissing("storage_migrations", "create_time", "DATETIME(3) NULL", "applied_at");
            addColumnIfMissing("storage_migrations", "update_time", "DATETIME(3) NULL", "create_time");
            normalize("storage_migrations", "uk_storage_migration_key", "migration_key");
            // 回填来源=applied_at（真实迁移生效时间），保留原值，不伪造迁移时刻。
            jdbc.update("UPDATE storage_migrations SET create_time=applied_at WHERE create_time IS NULL");
            jdbc.update("UPDATE storage_migrations SET update_time=applied_at WHERE update_time IS NULL");
            tightenAuditColumn("db/mysql/storage-migrations.sql", "storage_migrations", "create_time");
            tightenAuditColumn("db/mysql/storage-migrations.sql", "storage_migrations", "update_time");
        });
        polish("storage-migrations-column-comments-v1", "db/mysql/storage-migrations.sql");
    }

    private boolean registered(String migrationKey) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM storage_migrations WHERE migration_key=?", Long.class, migrationKey);
        return count != null && count > 0;
    }

    private void runOnce(String migrationKey, Runnable migration) {
        if (registered(migrationKey)) {
            return;
        }
        migration.run();
        jdbc.update("INSERT IGNORE INTO storage_migrations(migration_key) VALUES (?)", migrationKey);
    }

    /** One-way cutover: retire old UI conversations, preserve every underlying business fact. */
    private void retireLegacyPiConversations() {
        transactions.executeWithoutResult(status -> {
            int first = jdbc.update(
                    "INSERT IGNORE INTO storage_migrations(migration_key) VALUES (?)", LEGACY_CUTOVER_KEY);
            if (first == 1) {
                jdbc.update("UPDATE conversations SET cleared=1,update_time=CURRENT_TIMESTAMP(3) WHERE cleared=0");
            }
        });
    }

    // ------------------------------------------------------------------ candidate handles

    /** Copy published short handles only; historical full-ID-only facts keep a NULL handle. */
    private void backfillCandidateHandles() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, candidate_set_id, payload_json FROM device_candidate_sets WHERE handle IS NULL");
        for (Map<String, Object> row : rows) {
            String handle = readStoredHandle(String.valueOf(row.get("payload_json")));
            if (handle == null) continue;
            if (!CANDIDATE_HANDLE.matcher(handle).matches()) {
                throw new IllegalStateException("device_candidate_sets 旧行包含无效短句柄，"
                        + "拒绝自动迁移以避免改写历史引用；行 id=" + row.get("id")
                        + ", candidate_set_id=" + row.get("candidate_set_id")
                        + "，请人工核验该行后重试迁移");
            }
            jdbc.update("UPDATE device_candidate_sets SET handle=? WHERE id=? AND handle IS NULL",
                    handle, row.get("id"));
        }
    }

    private String readStoredHandle(String payloadJson) {
        try {
            JsonNode node = mapper.readTree(payloadJson);
            if (node == null || !node.isObject()) return "";
            JsonNode handle = node.get("handle");
            if (handle == null || handle.isNull()) return null;
            return handle.isTextual() ? handle.textValue() : "";
        } catch (Exception malformed) {
            return "";
        }
    }

    /** 同会话重复句柄属于历史数据缺陷：安全失败并列明位置，不任意改写历史引用。 */
    private void rejectDuplicateCandidateHandles() {
        List<String> duplicates = jdbc.query(
                "SELECT CONCAT(conversation_id, '/', handle, ' x', COUNT(*)) FROM device_candidate_sets "
                        + "WHERE handle IS NOT NULL GROUP BY conversation_id, handle HAVING COUNT(*)>1",
                (rs, row) -> rs.getString(1));
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException("device_candidate_sets 存在同会话重复句柄，拒绝自动加唯一键以避免改写历史引用："
                    + String.join("; ", duplicates)
                    + "；请人工合并重复行后重启，迁移可续跑");
        }
    }

    /** 句柄序号表一次性按旧句柄初始化每会话最大值；GREATEST 保证重跑不回退。 */
    private void initializeCandidateHandleSequences() {
        Map<String, Long> maxByConversation = new HashMap<>();
        jdbc.query("SELECT conversation_id, handle FROM device_candidate_sets WHERE handle IS NOT NULL",
                rs -> {
                    String handle = rs.getString(2);
                    if (handle != null && CANDIDATE_HANDLE.matcher(handle).matches()) {
                        maxByConversation.merge(rs.getString(1), Long.parseLong(handle.substring(1)), Math::max);
                    }
                });
        for (Map.Entry<String, Long> max : maxByConversation.entrySet()) {
            jdbc.update("INSERT INTO device_candidate_set_sequences"
                            + "(conversation_id,last_handle_no,create_time,update_time) "
                            + "VALUES(?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                            + "ON DUPLICATE KEY UPDATE last_handle_no=GREATEST(last_handle_no,VALUES(last_handle_no))",
                    max.getKey(), max.getValue());
        }
    }

    // ------------------------------------------------------------------ shared upgrades

    private void ensureBarrierIssuedAtColumn() {
        if (!columnExists("read_snapshot_credential_barriers", "issued_at")) {
            jdbc.execute("ALTER TABLE read_snapshot_credential_barriers ADD COLUMN issued_at DATETIME(3) NULL");
        }
        // 回填来源=行内 update_time，保留原值，不伪造发放时刻。
        jdbc.update("UPDATE read_snapshot_credential_barriers SET issued_at=update_time WHERE issued_at IS NULL");
        if (mysql) {
            jdbc.execute("ALTER TABLE read_snapshot_credential_barriers MODIFY COLUMN issued_at DATETIME(3) NOT NULL");
        } else if (columnNullable("read_snapshot_credential_barriers", "issued_at")) {
            jdbc.execute("ALTER TABLE read_snapshot_credential_barriers ALTER COLUMN issued_at SET NOT NULL");
        }
    }

    /** task_proposals 历史增列按基线定义补齐（含注释），幂等。 */
    private void upgradeTaskProposalColumns() {
        runOnce("task-proposal-lifecycle-columns-v1", () -> {
            TableDefinition proposals = schemas(AGENT_FACTS_RESOURCE).get("task_proposals");
            if (proposals == null) {
                throw new IllegalStateException("agent-facts.sql 缺少 task_proposals 定义");
            }
            for (String column : List.of("kind", "target_json", "before_json", "after_json",
                    "operation", "source", "expires_at")) {
                ensureParsedColumn("task_proposals", proposals.column(column));
            }
        });
    }

    /** task_plans 的 pause_reason 防御补列（原 TaskPlanSchemaUpgrade 迁入，定义从资源读取），幂等。 */
    private void upgradeTaskPlanPauseReason() {
        runOnce("task-plan-pause-reason-v1", () -> {
            TableDefinition plans = schemas(AGENT_FACTS_RESOURCE).get("task_plans");
            if (plans == null) {
                throw new IllegalStateException("agent-facts.sql 缺少 task_plans 定义");
            }
            ensureParsedColumn("task_plans", plans.column("pause_reason"));
        });
    }

    /**
     * task_phases 定义/结果/失败列的防御性补齐（共享基线建表后由任务切片负责升级）。
     * 表不存在时跳过，等待共享基线建表；definition_json 回填来源=同行 payload_json，保留原值。
     */
    private void ensureTaskPhaseColumns() {
        if (!tableExists("task_phases")) {
            return;
        }
        TableDefinition phases = schemas(AGENT_FACTS_RESOURCE).get("task_phases");
        if (phases == null) {
            throw new IllegalStateException("agent-facts.sql 缺少 task_phases 定义");
        }
        for (String column : List.of("definition_json", "result_json", "failure_reason")) {
            ensureParsedColumn("task_phases", phases.column(column));
        }
        // MySQL parses JSON text on assignment; H2 otherwise wraps legacy TEXT as a JSON string.
        String payload = mysql ? "payload_json" : "payload_json FORMAT JSON";
        jdbc.update("UPDATE task_phases SET definition_json=" + payload + " WHERE definition_json IS NULL");
    }

    private void ensureParsedColumn(String table, ColumnDefinition column) {
        if (column == null || columnExists(table, column.name())) {
            return;
        }
        if (mysql && column.comment() != null) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column.name() + " "
                    + column.definition() + " COMMENT '" + column.comment() + "'");
        } else {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column.name() + " " + column.definition());
        }
    }

    // ------------------------------------------------------------------ normalization primitives

    /**
     * 代理主键 + 自然唯一键：不清数据，旧行自动获得递增 id。先建自然唯一键再换主键，
     * 使自然幂等约束在迁移每一步（含中途崩溃与并发旧 writer）始终成立；
     * 新表已是 id 主键且自然键同名唯一时两步均自守卫跳过。
     */
    private void normalize(String table, String uniqueKeyName, String... naturalKeyColumns) {
        addUniqueKey(table, uniqueKeyName, naturalKeyColumns);
        ensureSurrogatePrimaryKey(table);
    }

    private void ensureSurrogatePrimaryKey(String table) {
        if (!columnExists(table, "id")) {
            jdbc.execute("ALTER TABLE " + table
                    + " ADD COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT UNIQUE FIRST");
        }
        List<String> primaryKey = primaryKeyColumns(table);
        if (primaryKey.isEmpty()) {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT pk_" + table + " PRIMARY KEY (id)");
        } else if (!(primaryKey.size() == 1 && primaryKey.get(0).equalsIgnoreCase("id"))) {
            jdbc.execute("ALTER TABLE " + table + " DROP PRIMARY KEY");
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT pk_" + table + " PRIMARY KEY (id)");
        }
        dropRedundantAutoIncrementIndex(table);
    }

    private void addColumnIfMissing(String table, String column, String definition, String afterColumn) {
        if (columnExists(table, column)) {
            return;
        }
        String position = afterColumn == null ? "" : " AFTER " + afterColumn;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + position);
    }

    private void addUniqueKey(String table, String name, String... columns) {
        if (uniqueIndexExists(table, name)) {
            return;
        }
        if (mysql) {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + name
                    + " UNIQUE (" + String.join(",", columns) + ")");
        } else {
            // H2 constraints can reuse the old primary-key index and prevent its removal.
            // An explicit independent index preserves uniqueness throughout the key migration.
            jdbc.execute("CREATE UNIQUE INDEX " + name + " ON " + table
                    + " (" + String.join(",", columns) + ")");
        }
    }

    /** 回填后的审计列收紧为 NOT NULL（并补默认值）；MySQL 走资源定义 MODIFY，与注释收敛一致。 */
    private void tightenAuditColumn(String resource, String table, String column) {
        ColumnDefinition definition = schemas(resource).get(table).column(column);
        if (mysql) {
            String modify = "ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + definition.definition();
            jdbc.execute(definition.comment() == null ? modify : modify + " COMMENT '" + definition.comment() + "'");
        } else {
            jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DEFAULT CURRENT_TIMESTAMP(3)");
            if (columnNullable(table, column)) {
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
            }
        }
    }

    /** 布尔列规范为 TINYINT UNSIGNED 前必须先校验旧值域，超域值安全失败而非截断。 */
    private void validateBooleanDomain(String table, String column) {
        Long invalid = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " NOT IN (0,1)", Long.class);
        if (invalid != null && invalid > 0) {
            throw new IllegalStateException("表 " + table + " 列 " + column + " 存在 " + invalid
                    + " 行非 0/1 旧值，禁止自动迁移为布尔规范（TINYINT UNSIGNED）；请人工核验值域后重试迁移");
        }
    }

    // ------------------------------------------------------------------ resources, parsing & metadata

    /** 新库基线：MySQL 原样执行资源（含注释）；H2 执行剥离注释子句的等价语句。 */
    private void runBaseline(String... resources) {
        if (mysql) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            for (String location : resources) {
                populator.addScript(new ClassPathResource(location));
            }
            populator.setContinueOnError(false);
            DatabasePopulatorUtils.execute(populator, dataSource);
            return;
        }
        for (TableDefinition table : schemas(resources).values()) {
            jdbc.execute(table.strippedCreateStatement());
        }
    }

    /** 一次性列注释收敛：按资源解析生成受控 ALTER，新库与旧库最终注释一致（仅 MySQL）。 */
    private void polish(String migrationKey, String... resources) {
        if (!mysql) {
            return;
        }
        runOnce(migrationKey, () -> {
            for (TableDefinition table : schemas(resources).values()) {
                if (!tableExists(table.name())) {
                    continue;
                }
                jdbc.execute(table.commentAlterStatement());
            }
        });
    }

    private boolean detectMysql() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getTables(connection.getCatalog(), null,
                    stored(metadata, table), new String[] {"TABLE"})) {
                return rows.next();
            }
        }));
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null,
                    stored(metadata, table), null)) {
                while (rows.next()) {
                    if (column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
                return false;
            }
        }));
    }

    private boolean columnNullable(String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), null,
                    stored(metadata, table), null)) {
                while (rows.next()) {
                    if (column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                        return !"NO".equalsIgnoreCase(rows.getString("IS_NULLABLE"));
                    }
                }
                return false;
            }
        }));
    }

    private List<String> primaryKeyColumns(String table) {
        List<String> columns = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> names = new ArrayList<>();
            try (ResultSet rows = metadata.getPrimaryKeys(connection.getCatalog(), null,
                    stored(metadata, table))) {
                while (rows.next()) {
                    names.add(rows.getString("COLUMN_NAME"));
                }
            }
            return names;
        });
        return columns == null ? List.of() : columns;
    }

    private boolean uniqueIndexExists(String table, String name) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), null,
                    stored(metadata, table), true, false)) {
                while (rows.next()) {
                    if (indexNameMatches(rows.getString("INDEX_NAME"), name)) {
                        return true;
                    }
                }
            }
            return false;
        }));
    }

    /** H2 将约束唯一索引改名为 <NAME>_INDEX_<hash>，比对时剥离该后缀。 */
    private static boolean indexNameMatches(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        String normalized = actual.toUpperCase(Locale.ROOT).replaceAll("_INDEX_[0-9A-Z]+$", "");
        return normalized.equals(expected.toUpperCase(Locale.ROOT));
    }

    /** 去掉补 id 时自动生成的 UNIQUE(id) 冗余索引（主键已覆盖 id）；仅 MySQL，H2 约束索引不可如此删除且无害。 */
    private void dropRedundantAutoIncrementIndex(String table) {
        if (!mysql) {
            return;
        }
        List<String> redundant = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, List<String>> indexes = new LinkedHashMap<>();
            try (ResultSet rows = metadata.getIndexInfo(connection.getCatalog(), null,
                    stored(metadata, table), true, false)) {
                while (rows.next()) {
                    String index = rows.getString("INDEX_NAME");
                    if (index == null || "PRIMARY".equalsIgnoreCase(index)) {
                        continue;
                    }
                    indexes.computeIfAbsent(index, ignored -> new ArrayList<>())
                            .add(Objects.requireNonNullElse(rows.getString("COLUMN_NAME"), ""));
                }
            }
            List<String> droppable = new ArrayList<>();
            for (Map.Entry<String, List<String>> index : indexes.entrySet()) {
                if (index.getValue().size() == 1 && "id".equalsIgnoreCase(index.getValue().get(0))) {
                    droppable.add(index.getKey());
                }
            }
            return droppable;
        });
        for (String index : redundant == null ? List.<String>of() : redundant) {
            jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + index);
        }
    }

    private static String stored(DatabaseMetaData metadata, String identifier) throws java.sql.SQLException {
        return metadata.storesUpperCaseIdentifiers() ? identifier.toUpperCase(Locale.ROOT) : identifier;
    }

    // ------------------------------------------------------------------ resource parsing

    /** 解析 db/mysql 资源的 CREATE TABLE 语句：表名、表注释、逐列定义与列注释（结构唯一权威）。 */
    private static Map<String, TableDefinition> schemas(String... resources) {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();
        for (String resource : resources) {
            for (String statement : statements(resource)) {
                Matcher create = CREATE_TABLE.matcher(statement);
                if (!create.find()) {
                    continue;
                }
                String table = create.group(1);
                tables.put(table, new TableDefinition(table, create.group(3),
                        create.group(2), statement));
            }
        }
        return tables;
    }

    private static List<String> statements(String resource) {
        String text;
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("无法读取结构资源 " + resource, unreadable);
        }
        List<String> parsed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(trimmed);
            if (trimmed.endsWith(";")) {
                parsed.add(current.substring(0, current.length() - 1));
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            parsed.add(current.toString());
        }
        return parsed;
    }

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)\\s*\\((.*)\\)\\s*ENGINE\\s*=\\s*\\w+"
                    + "(?:\\s*COMMENT\\s*=\\s*'((?:[^']|'')*)')?",
            Pattern.DOTALL);
    /** 列注释子句（含前导空白），用于 H2 等价建表时剥离。 */
    private static final String COLUMN_COMMENT_PATTERN = "\\s+COMMENT\\s+'(?:[^']|'')*'";
    private static final String TABLE_COMMENT_PATTERN = "(?:\\s*COMMENT\\s*=\\s*'(?:[^']|'')*')";

    /** 表结构（来自资源解析）：提供剥离注释的建表语句与注释收敛 ALTER。 */
    private static final class TableDefinition {
        private final String name;
        private final String tableComment;
        private final String createStatement;
        private final List<ColumnDefinition> columns;

        TableDefinition(String name, String tableComment, String body, String createStatement) {
            this.name = name;
            this.tableComment = tableComment;
            this.createStatement = createStatement;
            this.columns = new ArrayList<>();
            for (String rawLine : body.split(",\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("PRIMARY KEY") || line.startsWith("UNIQUE KEY")
                        || line.startsWith("KEY ") || line.startsWith("CONSTRAINT")
                        || line.startsWith("INDEX")) {
                    continue;
                }
                Matcher comment = Pattern.compile(COLUMN_COMMENT_PATTERN + "$").matcher(line);
                String columnComment = null;
                String definition = line;
                if (comment.find()) {
                    columnComment = comment.group().trim()
                            .replaceFirst("(?i)^COMMENT\\s+'", "").replaceFirst("'$", "");
                    definition = line.substring(0, comment.start());
                }
                String columnName = definition.split("\\s+")[0];
                definition = definition.substring(columnName.length()).trim()
                        .replaceFirst("(?i)\\s+PRIMARY\\s+KEY$", "").trim();
                columns.add(new ColumnDefinition(columnName, definition, columnComment));
            }
        }

        String name() {
            return name;
        }

        ColumnDefinition column(String columnName) {
            return columns.stream()
                    .filter(column -> column.name().equalsIgnoreCase(columnName)).findFirst().orElse(null);
        }

        /** H2 等价建表：剥离列注释与表注释，保留列/键定义。 */
        String strippedCreateStatement() {
            return createStatement
                    .replaceAll(COLUMN_COMMENT_PATTERN, "")
                    .replaceFirst(TABLE_COMMENT_PATTERN + "\\s*$", "");
        }

        /** MySQL 注释收敛：一条受控 ALTER 同步表注释与逐列注释（定义与资源逐字一致）。 */
        String commentAlterStatement() {
            StringBuilder alter = new StringBuilder("ALTER TABLE ").append(name);
            if (tableComment != null && !tableComment.isEmpty()) {
                alter.append(" COMMENT='").append(tableComment).append("'");
            }
            for (ColumnDefinition column : columns) {
                if (column.comment() == null) {
                    continue;
                }
                alter.append(", MODIFY COLUMN ").append(column.name())
                        .append(' ').append(column.definition())
                        .append(" COMMENT '").append(column.comment()).append('\'');
            }
            return alter.toString();
        }
    }

    /** 单列定义（不含列名与注释）及其资源内注释。 */
    private record ColumnDefinition(String name, String definition, String comment) { }
}
