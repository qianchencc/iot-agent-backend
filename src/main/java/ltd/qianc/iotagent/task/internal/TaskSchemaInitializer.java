package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task-specific additive schema. It is deliberately independent from the shared
 * agent-facts baseline so the task slice can evolve protected identity and phase
 * evidence without racing other owners' migrations.
 */
public final class TaskSchemaInitializer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TaskSchemaInitializer(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
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
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_device_dispatch_reservations ("
                + "task_id VARCHAR(64) NOT NULL, device_id VARCHAR(64) NOT NULL, phase_id VARCHAR(64) NOT NULL,"
                + "kind VARCHAR(16) NOT NULL, reserved_at DATETIME NOT NULL,"
                + "create_time DATETIME NOT NULL, update_time DATETIME NOT NULL,"
                + "PRIMARY KEY (task_id, device_id), KEY ix_task_dispatch_reservation_phase (phase_id)) ENGINE=InnoDB");
        seedDispatchReservations();
    }

    /**
     * Migration for pre-reservation data: every device a finished adjust-mode
     * checkpoint actually dispatched or consciously yielded keeps its
     * once-per-task fact, so later checkpoints of the same task never
     * re-adjust it. Devices that were merely classified (not_needed, unknown,
     * adjustable-without-dispatch) carry no dispatch fact and stay free.
     */
    private void seedDispatchReservations() {
        jdbc.query("SELECT task_id, phase_id, ended_at, result_json FROM task_phases "
                        + "WHERE result_json IS NOT NULL AND result_json LIKE '%ac_inspection%'",
                (rs, row) -> {
                    JsonNode result;
                    try {
                        result = mapper.readTree(rs.getString("result_json"));
                    } catch (Exception invalid) {
                        return null;
                    }
                    Set<String> dispatched = new LinkedHashSet<>();
                    for (JsonNode block : result.path("observations")) {
                        if (!"ac_inspection".equals(block.path("type").asText(""))) continue;
                        if (!"adjust_to_26".equals(block.path("mode").asText(""))) continue;
                        for (JsonNode device : block.path("devices")) {
                            String id = device.path("deviceId").asText("");
                            String disposition = device.path("disposition").asText("");
                            if (id.isBlank()) continue;
                            if ("adjusted".equals(disposition) || "satisfied".equals(disposition)
                                    || "not_dispatched".equals(disposition)) {
                                dispatched.add(id);
                            }
                        }
                    }
                    if (dispatched.isEmpty()) return null;
                    Timestamp at = rs.getTimestamp("ended_at") == null
                            ? Timestamp.from(Instant.now()) : rs.getTimestamp("ended_at");
                    for (String deviceId : dispatched) {
                        jdbc.update("INSERT IGNORE INTO task_device_dispatch_reservations "
                                        + "(task_id, device_id, phase_id, kind, reserved_at, create_time, update_time) "
                                        + "VALUES (?,?,?, 'attempted', ?, ?, ?)",
                                rs.getString("task_id"), deviceId, rs.getString("phase_id"), at, at, at);
                    }
                    return null;
                });
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
