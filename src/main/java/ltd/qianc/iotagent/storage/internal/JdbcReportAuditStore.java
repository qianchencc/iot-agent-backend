package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventQuery;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.audit.AuditMember;
import ltd.qianc.iotagent.audit.AuditMemberIndex;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcReportAuditStore extends AbstractJdbcFactSupport implements ReportFactStore, AuditEventStore {
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
    @Override public AuditEventFact append(AuditEventFact event) { Instant at = event.createdAt() == null ? Instant.now() : event.createdAt(); jdbc.update("INSERT INTO audit_events(event_id,tenant_code,event_type,payload_json,create_time,update_time) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=event_id", event.eventId(), event.tenantCode(), event.eventType(), event.payloadJson(), time(at), time(at)); return findByEventId(event.eventId()).orElseGet(() -> new AuditEventFact(event.eventId(), event.tenantCode(), event.eventType(), event.payloadJson(), at)); }
    @Override public List<AuditEventFact> readByTenant(String tenantCode) { return jdbc.query("SELECT event_id,tenant_code,event_type,payload_json,create_time,id FROM audit_events WHERE tenant_code=? ORDER BY create_time,id", JdbcReportAuditStore::eventRow, tenantCode); }
    @Override public Optional<AuditEventFact> findByEventId(String eventId) { return one("SELECT event_id,tenant_code,event_type,payload_json,create_time,id FROM audit_events WHERE event_id=?", JdbcReportAuditStore::eventRow, eventId); }
    @Override public List<String> tenants() { return jdbc.queryForList("SELECT DISTINCT tenant_code FROM audit_events ORDER BY tenant_code", String.class); }

    @Override public Map<String, String> knownNames(java.util.Set<String> mobileNumbers) {
        if (mobileNumbers.isEmpty()) return Map.of();
        String sql = "SELECT mobile_number,MIN(display_name) display_name FROM (SELECT "
                + "TRIM(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.mobileNumber'))) mobile_number,"
                + "TRIM(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.displayName'))) display_name"
                + " FROM audit_events WHERE NOT (" + absent("$.principalId") + " OR " + absent("$.identityRealm") + ")"
                + " AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source')) IN ('company','office')) members"
                + " WHERE mobile_number IN (" + placeholders(mobileNumbers.size()) + ")"
                + " AND display_name IS NOT NULL AND display_name NOT IN ('','null')"
                + " GROUP BY mobile_number HAVING COUNT(DISTINCT display_name)=1";
        Map<String, String> names = new LinkedHashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) row ->
                names.put(row.getString("mobile_number"), row.getString("display_name")), mobileNumbers.toArray());
        return names;
    }

    private static AuditEventFact eventRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AuditEventFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant(), rs.getLong(6));
    }

    /** Bounded cursor pagination: every filter is pushed down, tenant history never loads into memory. */
    @Override public List<AuditEventFact> query(AuditEventQuery query) {
        StringBuilder sql = new StringBuilder(
                "SELECT event_id,tenant_code,event_type,payload_json,create_time,id FROM audit_events WHERE tenant_code=?");
        List<Object> args = new ArrayList<>();
        args.add(query.tenantCode());
        if (query.unresolvedOnly()) { sql.append(" AND (").append(absent("$.principalId"))
                .append(" OR ").append(absent("$.identityRealm")).append(')'); }
        else if (query.principalId() != null) {
            sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.principalId'))=?");
            args.add(query.principalId());
        }
        if (query.identityRealm() != null) {
            sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.identityRealm'))=?");
            args.add(query.identityRealm());
        }
        if (query.source() != null) {
            sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source'))=?");
            args.add(query.source());
        }
        if (query.eventType() != null) {
            sql.append(" AND event_type=?");
            args.add(query.eventType());
        }
        if (query.fromAt() != null) { sql.append(" AND create_time>=?"); args.add(time(query.fromAt())); }
        if (query.toAt() != null) { sql.append(" AND create_time<=?"); args.add(time(query.toAt())); }
        if (query.cursorAt() != null) {
            sql.append(" AND (create_time<? OR (create_time=? AND id<?))");
            args.add(time(query.cursorAt())); args.add(time(query.cursorAt())); args.add(query.cursorId());
        }
        if (!query.statuses().isEmpty() || !query.eventTypes().isEmpty()) {
            String statusAbsent = absent("$.status");
            if (query.excludeEventTypes()) {
                // UNKNOWN result: no payload status and no result-bearing event type.
                sql.append(" AND (").append(statusAbsent).append(") AND event_type NOT IN (")
                        .append(placeholders(query.eventTypes().size())).append(')');
                args.addAll(query.eventTypes());
            } else if (query.statuses().isEmpty()) {
                sql.append(" AND (").append(statusAbsent).append(") AND event_type IN (")
                        .append(placeholders(query.eventTypes().size())).append(')');
                args.addAll(query.eventTypes());
            } else if (query.eventTypes().isEmpty()) {
                sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.status')) IN (")
                        .append(placeholders(query.statuses().size())).append(')');
                args.addAll(query.statuses());
            } else {
                sql.append(" AND (JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.status')) IN (")
                        .append(placeholders(query.statuses().size())).append(") OR ((")
                        .append(statusAbsent).append(") AND event_type IN (")
                        .append(placeholders(query.eventTypes().size())).append(")))");
                args.addAll(query.statuses()); args.addAll(query.eventTypes());
            }
        }
        sql.append(" ORDER BY create_time DESC, id DESC LIMIT ?");
        args.add(query.limit());
        return jdbc.query(sql.toString(), JdbcReportAuditStore::eventRow, args.toArray());
    }

    /** Grouped member index; the database aggregates so the read stays bounded. */
    @Override public AuditMemberIndex memberIndex(String tenantCode, String identityRealm,
            String principalId, int limit, int offset) {
        String filter = "";
        List<Object> filterArgs = new ArrayList<>();
        filterArgs.add(tenantCode);
        if (identityRealm != null) { filter += " AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.identityRealm'))=?"; filterArgs.add(identityRealm); }
        if (principalId != null) { filter += " AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.principalId'))=?"; filterArgs.add(principalId); }
        List<Object> args = new ArrayList<>(filterArgs);
        String resolvedPredicate = "NOT (" + absent("$.identityRealm") + " OR " + absent("$.principalId") + ")";
        String realmGroup = "CASE WHEN " + resolvedPredicate + " THEN JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.identityRealm')) ELSE '' END";
        String principalGroup = "CASE WHEN " + resolvedPredicate + " THEN JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.principalId')) ELSE '' END";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT 1 FROM audit_events WHERE tenant_code=?"
                + filter + " GROUP BY " + realmGroup + "," + principalGroup + ") t",
                Long.class, args.toArray());
        if (total == null || total == 0 || offset >= total) return new AuditMemberIndex(List.of(), total == null ? 0 : total);
        args = new ArrayList<>(filterArgs);
        args.add(limit); args.add(offset);
        List<AuditMember> members = jdbc.query(
                "SELECT " + realmGroup + " realm," + principalGroup + " principal,"
                + " MAX(COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.displayName')), ''),"
                + " NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.creatorName')), ''))) display_name,"
                + " MAX(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.mobileNumber')), '')) mobile_number,"
                + " MIN(create_time) first_at, MAX(create_time) last_at, COUNT(*) cnt,"
                + " SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source'))='company' THEN 1 ELSE 0 END) company_count,"
                + " SUM(CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source'))='office' THEN 1 ELSE 0 END) office_count"
                + " FROM audit_events WHERE tenant_code=?" + filter
                + " GROUP BY realm, principal ORDER BY last_at DESC, realm, principal LIMIT ? OFFSET ?",
                (rs, row) -> {
                    String realm = rs.getString("realm");
                    String principal = rs.getString("principal");
                    boolean resolved = realm != null && !realm.isBlank() && principal != null && !principal.isBlank();
                    long count = rs.getLong("cnt");
                    long company = rs.getLong("company_count");
                    long office = rs.getLong("office_count");
                    Map<String, Long> sources = new LinkedHashMap<>();
                    if (company > 0) sources.put("company", company);
                    if (office > 0) sources.put("office", office);
                    if (count - company - office > 0) sources.put("unknown", count - company - office);
                    return new AuditMember(tenantCode, resolved ? realm : null, resolved ? principal : null,
                            rs.getString("display_name"), rs.getString("mobile_number"), resolved, count, sources,
                            rs.getTimestamp("first_at").toInstant(), rs.getTimestamp("last_at").toInstant());
                }, args.toArray());
        return new AuditMemberIndex(members, total);
    }

    private static String absent(String path) {
        String extract = "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'" + path + "'))";
        return "(" + extract + " IS NULL OR " + extract + "='' OR " + extract + "='null')";
    }

    private static String placeholders(int count) {
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < count; index++) { if (index > 0) sql.append(','); sql.append('?'); }
        return sql.toString();
    }
}
