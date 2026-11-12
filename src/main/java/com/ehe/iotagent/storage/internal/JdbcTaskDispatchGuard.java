package com.ehe.iotagent.storage.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import com.ehe.iotagent.task.TaskDispatchGuard;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Durable per-checkpoint device reservations. The atomic uniqueness constraint
 * prevents duplicate sends without blocking the next scheduled checkpoint.
 */
public final class JdbcTaskDispatchGuard implements TaskDispatchGuard {
    /** Phase executing, task dispatchable (scheduled or interval-executing), and the window (if any) not ended; the boundary checkpoint stays dispatchable. */
    private static final String DISPATCHABLE =
            "SELECT 1 FROM task_phases p JOIN task_facts t ON t.task_id=p.task_id "
            + "WHERE p.phase_id=? AND p.task_id=? AND p.status='EXECUTING' AND t.status IN ('SCHEDULED','EXECUTING') "
            + "AND (? IS NULL OR ? >= ? OR p.due_at = ?)";

    private final JdbcTemplate jdbc;

    public JdbcTaskDispatchGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> reservedDevices(String taskId, String phaseId) {
        return new LinkedHashSet<>(jdbc.query(
                "SELECT device_id FROM task_device_dispatch_reservations WHERE task_id=? AND phase_id=? ORDER BY reserved_at, device_id",
                (rs, row) -> rs.getString(1), taskId, phaseId));
    }
    @Override
    public boolean dispatchable(String taskId, String phaseId, Instant now, Instant windowEnd) {
        Timestamp boundary = windowEnd == null ? null : Timestamp.from(windowEnd);
        Timestamp at = Timestamp.from(now);
        Boolean present = jdbc.query(DISPATCHABLE, rs -> rs.next() ? Boolean.TRUE : null,
                phaseId, taskId, boundary, boundary, at, boundary);
        return Boolean.TRUE.equals(present);
    }

    @Override
    public boolean reserve(String taskId, String phaseId, String deviceId, Instant now, Instant windowEnd) {
        Timestamp boundary = windowEnd == null ? null : Timestamp.from(windowEnd);
        Timestamp at = Timestamp.from(now);
        try {
            return jdbc.update("INSERT INTO task_device_dispatch_reservations "
                    + "(task_id, device_id, phase_id, kind, reserved_at, create_time, update_time) "
                    + "SELECT ?,?,?, 'attempted', ?, ?, ? WHERE EXISTS (" + DISPATCHABLE + ")",
                    taskId, deviceId, phaseId, at, at, at,
                    phaseId, taskId, boundary, boundary, at, boundary) == 1;
        } catch (DuplicateKeyException alreadyReserved) {
            // This checkpoint already owns the device; never dispatch it twice.
            return false;
        }
    }

    @Override
    public void yield(String taskId, String phaseId, String deviceId, Instant at) {
        Timestamp moment = Timestamp.from(at);
        try {
            jdbc.update("INSERT IGNORE INTO task_device_dispatch_reservations "
                    + "(task_id, device_id, phase_id, kind, reserved_at, create_time, update_time) "
                    + "VALUES (?,?,?, 'yielded', ?, ?, ?)",
                    taskId, deviceId, phaseId, moment, moment, moment);
        } catch (RuntimeException unreadableFact) {
            // A yield fact that cannot be recorded must not be silently forgotten:
            // without it a duplicate execution could re-attempt this checkpoint.
            throw new com.ehe.iotagent.error.BusinessException(
                    "TASK_RESERVATION_UNAVAILABLE", "任务设备调温让行事实写入失败", 503);
        }
    }
}
