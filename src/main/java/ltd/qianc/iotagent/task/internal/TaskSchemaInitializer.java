package ltd.qianc.iotagent.task.internal;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task-specific additive schema. It is deliberately independent from the shared
 * agent-facts baseline so the task slice can evolve protected identity and phase
 * evidence without racing other owners' migrations.
 */
public final class TaskSchemaInitializer {
    private final JdbcTemplate jdbc;

    public TaskSchemaInitializer(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public void initialize() {
        ensureColumn("task_phases", "definition_json", "JSON NULL");
        ensureColumn("task_phases", "result_json", "JSON NULL");
        ensureColumn("task_phases", "failure_reason", "VARCHAR(1024) NULL");
        jdbc.update("UPDATE task_phases SET definition_json=payload_json WHERE definition_json IS NULL");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_execution_identities ("
                + "owner_id VARCHAR(64) NOT NULL, owner_kind VARCHAR(16) NOT NULL,"
                + "tenant_code VARCHAR(128) NOT NULL, owner_key CHAR(64) NOT NULL,"
                + "payload MEDIUMTEXT NOT NULL, create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,"
                + "PRIMARY KEY (owner_id), KEY ix_task_execution_identity_kind (owner_kind)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_report_attempts ("
                + "task_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,"
                + "last_error VARCHAR(1024) NULL, next_attempt_at DATETIME NULL,"
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,"
                + "PRIMARY KEY (task_id)) ENGINE=InnoDB");
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
