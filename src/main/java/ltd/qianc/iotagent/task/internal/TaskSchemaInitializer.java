package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.sql.DataSource;
import ltd.qianc.iotagent.schema.SchemaMigrations;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Task-specific additive schema. It is deliberately independent from the shared
 * agent-facts baseline so the task slice can evolve protected identity and phase
 * evidence without racing other owners' migrations. All DDL and legacy-key
 * upgrades (including the defensive task_phases columns) live in
 * {@link SchemaMigrations}; this initializer keeps only the task-owned
 * historical dispatch seeding over durable facts.
 */
public final class TaskSchemaInitializer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TaskSchemaInitializer(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    public void initialize() {
        new SchemaMigrations(jdbc.getDataSource()).ensureTaskLifecycle();
        seedDispatchReservations();
    }

    /**
     * Migration for pre-reservation data: every device a finished adjust-mode
     * checkpoint actually dispatched or consciously yielded keeps its
     * per-checkpoint fact, preventing duplicate sends after restart. Later
     * checkpoints remain independent; historical execution results are unchanged.
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
}
