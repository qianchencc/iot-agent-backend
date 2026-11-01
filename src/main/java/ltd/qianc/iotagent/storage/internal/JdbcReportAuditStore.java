package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcReportAuditStore extends JdbcFactSupport implements ReportFactStore, AuditEventStore {
    public JdbcReportAuditStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public ReportFact save(ReportFact report) {
        jdbc.update("INSERT INTO report_facts(report_id,source,payload_json,create_time,update_time) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE report_id=report_id",
                report.reportId(), report.source(), report.payloadJson(), time(Instant.now()), time(Instant.now()));
        return find(report.reportId()).orElse(report);
    }
    @Override public Optional<ReportFact> find(String id) { return one("SELECT report_id,source,payload_json FROM report_facts WHERE report_id=?", (rs, row) -> new ReportFact(rs.getString(1), rs.getString(2), rs.getString(3)), id); }
    @Override public Optional<ReportFact> findLatestByTaskId(String taskId) {
        return one("SELECT report_id,source,payload_json FROM report_facts "
                        + "WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.taskId'))=? "
                        + "ORDER BY update_time DESC, create_time DESC LIMIT 1",
                (rs, row) -> new ReportFact(rs.getString(1), rs.getString(2), rs.getString(3)), taskId);
    }
    @Override public AuditEventFact append(AuditEventFact event) { jdbc.update("INSERT INTO audit_events(event_id,tenant_code,event_type,payload_json,create_time,update_time) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_type=VALUES(event_type),payload_json=VALUES(payload_json),update_time=VALUES(update_time)", event.eventId(), event.tenantCode(), event.eventType(), event.payloadJson(), time(Instant.now()), time(Instant.now())); return event; }
    @Override public List<AuditEventFact> readByTenant(String tenantCode) { return jdbc.query("SELECT event_id,tenant_code,event_type,payload_json FROM audit_events WHERE tenant_code=? ORDER BY create_time,id", (rs, row) -> new AuditEventFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), tenantCode); }
}
