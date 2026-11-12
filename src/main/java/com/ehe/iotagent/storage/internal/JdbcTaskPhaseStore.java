package com.ehe.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import com.ehe.iotagent.task.TaskPhaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskPhaseStore extends AbstractJdbcFactSupport implements TaskPhaseStore {
    public JdbcTaskPhaseStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public List<TaskPhase> due(Instant now, int limit) {
        int bounded = Math.max(1, Math.min(limit, 6));
        return jdbc.query("SELECT p.task_id,p.phase_id,p.version,p.source,"
                        + "COALESCE(p.definition_json,p.payload_json),p.due_at,p.status "
                        + "FROM task_phases p JOIN task_facts t ON t.task_id=p.task_id "
                        + "WHERE p.status='PENDING' AND p.due_at<=? "
                        + "AND t.status IN ('SCHEDULED','EXECUTING') "
                        + "AND NOT EXISTS (SELECT 1 FROM task_phases q WHERE q.task_id=p.task_id "
                        + "AND q.status IN ('PENDING','EXECUTING') AND (q.due_at<p.due_at OR (q.due_at=p.due_at AND q.id<p.id))) "
                        + "ORDER BY p.due_at,p.id LIMIT ?", this::map, time(now), bounded);
    }
    @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            int claimed = jdbc.update("UPDATE task_phases SET status='EXECUTING',version=version+1,claimed_at=?,update_time=? "
                            + "WHERE phase_id=? AND version=? AND status='PENDING' "
                            + "AND EXISTS (SELECT 1 FROM task_facts t WHERE t.task_id=task_phases.task_id "
                            + "AND t.status IN ('SCHEDULED','EXECUTING'))",
                    time(claimedAt), time(claimedAt), phaseId, expectedVersion);
            if (claimed == 0) return false;
            int progressed = jdbc.update("UPDATE task_facts SET status='EXECUTING',version=version+1,update_time=? "
                            + "WHERE task_id=(SELECT task_id FROM task_phases WHERE phase_id=?) "
                            + "AND status IN ('SCHEDULED','EXECUTING')",
                    time(claimedAt), phaseId);
            if (progressed == 0) transaction.setRollbackOnly();
            return progressed == 1;
        }));
    }
    @Override public Optional<TaskPhase> find(String phaseId) {
        return one("SELECT task_id,phase_id,version,source,COALESCE(definition_json,payload_json),due_at,status "
                + "FROM task_phases WHERE phase_id=?", this::map, phaseId);
    }
    @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) {
        return Boolean.TRUE.equals(transactions.execute(transaction -> {
            int finished = jdbc.update("UPDATE task_phases SET status=?,version=version+1,result_json=?,failure_reason=?,ended_at=?,update_time=? "
                            + "WHERE phase_id=? AND version=? AND status='EXECUTING'",
                    status, payloadJson, failureReason(payloadJson), time(endedAt), time(endedAt), phaseId, expectedVersion);
            if (finished == 0) return false;
            jdbc.update("UPDATE task_facts SET version=version+1,update_time=? "
                            + "WHERE task_id=(SELECT task_id FROM task_phases WHERE phase_id=?)",
                    time(endedAt), phaseId);
            return true;
        }));
    }
    @Override public int recoverExecuting(Instant at) { return recoverInterrupted(at).size(); }
    @Override public List<TaskPhase> recoverInterrupted(Instant at) {
        return transactions.execute(status -> {
            List<TaskPhase> executing = jdbc.query("SELECT task_id,phase_id,version,source,"
                    + "COALESCE(definition_json,payload_json),due_at,status FROM task_phases WHERE status='EXECUTING' FOR UPDATE", this::map);
            if (executing.isEmpty()) return List.of();
            String result = "{\"outcome\":\"unknown\",\"reason\":\"service_restart\"}";
            jdbc.update("UPDATE task_phases SET status='INTERRUPTED',version=version+1,result_json=?,"
                            + "failure_reason='执行中断，部分指令结果未知',ended_at=?,update_time=? WHERE status='EXECUTING'",
                    result, time(at), time(at));
            return executing.stream().map(phase -> new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "INTERRUPTED")).toList();
        });
    }
    @Override public boolean allTerminal(String taskId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id=? AND status IN ('PENDING','RETRY','EXECUTING')", Integer.class, taskId);
        return active == null || active == 0;
    }
    @Override public List<String> outcomes(String taskId) {
        // Cancelled phases stay in the aggregate input exactly like the Runtime's
        // reconcileTask: an invalidation after earlier completions lands on PARTIAL.
        return jdbc.query("SELECT status FROM task_phases WHERE task_id=? ORDER BY id", (rs, row) -> rs.getString(1), taskId);
    }
    @Override public boolean miss(String phaseId, Instant at, String reason) {
        String result = "{\"outcome\":\"missed\"}";
        return jdbc.update("UPDATE task_phases SET status='MISSED',version=version+1,result_json=?,"
                        + "failure_reason=?,ended_at=?,update_time=? WHERE phase_id=? AND status='PENDING'",
                result, reason, time(at), time(at), phaseId) == 1;
    }
    @Override public List<TaskPhase> missElapsed(Instant at) {
        return transactions.execute(status -> {
            List<TaskPhase> elapsed = jdbc.query("SELECT p.task_id,p.phase_id,p.version,p.source,"
                    + "COALESCE(p.definition_json,p.payload_json),p.due_at,p.status FROM task_phases p "
                    + "JOIN task_facts t ON t.task_id=p.task_id "
                    + "WHERE p.status='PENDING' AND p.due_at<? "
                    + "AND JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.sourcePlanId')) IS NOT NULL "
                    + "ORDER BY p.due_at,p.id FOR UPDATE", this::map, time(at));
            if (elapsed.isEmpty()) return List.of();
            jdbc.update("UPDATE task_phases p SET p.status='MISSED',p.version=p.version+1,"
                    + "p.result_json='{\"outcome\":\"missed\"}',p.failure_reason='服务重启时检查点已过，未下发',"
                    + "p.ended_at=?,p.update_time=? WHERE p.status='PENDING' AND p.due_at<? "
                    + "AND EXISTS (SELECT 1 FROM task_facts t WHERE t.task_id=p.task_id "
                    + "AND JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.sourcePlanId')) IS NOT NULL)",
                    time(at), time(at), time(at));
            return elapsed.stream().map(phase -> new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "MISSED")).toList();
        });
    }
    @Override public int cancelPending(String taskId, String reason, Instant at) {
        return jdbc.update("UPDATE task_phases SET status='CANCELLED',version=version+1,"
                        + "result_json='{\"outcome\":\"cancelled\"}',failure_reason=?,ended_at=?,update_time=? "
                        + "WHERE task_id=? AND status='PENDING'",
                reason, time(at), time(at), taskId);
    }
    @Override public List<String> readyToFinalize(Instant now, int limit) {
        return intervalWindows("endsAt", now, limit, true);
    }

    @Override public List<String> startedIntervals(Instant now, int limit) {
        // Runtime advanceIntervals flips scheduled interval tasks to executing
        // once interval_start passed; the scanner performs the fact CAS.
        return intervalWindows("startsAt", now, limit, false);
    }

    private List<String> intervalWindows(String boundaryField, Instant now, int limit, boolean terminalOnly) {
        int bounded = Math.max(1, Math.min(limit, 50));
        String boundary = "$.interval." + boundaryField;
        String sql = "SELECT t.id, t.task_id, t.update_time, "
                + "JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'" + boundary + "')) AS boundary_value "
                + "FROM task_facts t WHERE "
                + (terminalOnly ? "t.status IN ('SCHEDULED','EXECUTING') " : "t.status='SCHEDULED' ")
                + (terminalOnly ? "AND EXISTS (SELECT 1 FROM task_phases p WHERE p.task_id=t.task_id) "
                    + "AND NOT EXISTS (SELECT 1 FROM task_phases p WHERE p.task_id=t.task_id "
                    + "AND p.status IN ('PENDING','RETRY','EXECUTING')) "
                    : "AND JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'" + boundary + "')) IS NOT NULL ")
                + "AND (t.update_time > ? OR (t.update_time = ? AND t.id > ?)) "
                + "ORDER BY t.update_time, t.id LIMIT 50";
        List<String> matched = new java.util.ArrayList<>();
        Object lastUpdate = new java.sql.Timestamp(0);
        long lastId = 0;
        while (matched.size() < bounded) {
            List<Map<String, Object>> page = jdbc.queryForList(sql, lastUpdate, lastUpdate, lastId);
            if (page.isEmpty()) break;
            for (Map<String, Object> row : page) {
                Object value = row.get("boundary_value");
                Instant boundaryAt = parseIso(value == null ? null : String.valueOf(value));
                if ((terminalOnly && value == null) || (boundaryAt != null && !now.isBefore(boundaryAt))) {
                    matched.add(String.valueOf(row.get("task_id")));
                    if (matched.size() >= bounded) break;
                }
            }
            Map<String, Object> last = page.get(page.size() - 1);
            // Preserve JDBC's DATETIME representation; treating LocalDateTime
            // as UTC shifts an Asia/Shanghai cursor and skips the next page.
            lastUpdate = last.get("update_time");
            lastId = ((Number) last.get("id")).longValue();
            if (page.size() < 50) break;
        }
        return matched;
    }

    private static Instant parseIso(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException invalid) {
            return null;
        }
    }
    @Override public List<PhaseResult> terminalPhases(String taskId) {
        return phasesWhere(taskId, " AND p.status IN ('COMPLETED','PARTIAL','FAILED','UNCONFIRMED','INTERRUPTED','CANCELLED','MISSED','SATISFIED')");
    }
    @Override public List<PhaseResult> allPhases(String taskId) {
        return phasesWhere(taskId, "");
    }
    private List<PhaseResult> phasesWhere(String taskId, String statusFilter) {
        return jdbc.query("SELECT p.task_id,p.phase_id,p.due_at,p.status,"
                        + "COALESCE(p.definition_json,p.payload_json),COALESCE(p.result_json,'{}'),"
                        + "p.claimed_at,p.ended_at,p.failure_reason FROM task_phases p "
                        + "WHERE p.task_id=?" + statusFilter + " ORDER BY p.due_at,p.id",
                (rs, row) -> new PhaseResult(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                        rs.getString(9)), taskId);
    }
    private TaskPhase map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskPhase(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getString(7));
    }
    private String failureReason(String payloadJson) {
        try {
            JsonNode value = mapper.readTree(payloadJson);
            String reason = value.path("reason").asText("");
            if (reason.isBlank()) reason = value.path("error").asText("");
            return reason.isBlank() ? null : reason;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException invalid) {
            throw new com.ehe.iotagent.error.BusinessException("INVALID_TASK_RESULT", "任务执行记录无法读取", 503);
        }
    }
}
