package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/** Shared alarm facts, independent local grants and an explicit background-only source boundary. */
public final class JdbcSafetyHistory implements SafetyHistory {
    private static final List<String> TYPES = List.of("gas", "smoke", "water");
    private static final String FIELDS = "scope_key,event_key,ledger,event_id,device_id,recorded_device_name,occurred_at,order_key,alarm,report";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Function<RequestIdentity, DeviceDirectoryBatch> directory;
    private final Function<RequestIdentity, String> keys;
    private final Function<AuthorizedDeviceScope, Map<String, Map<String, Object>>> currentDevices;
    private final Clock clock;
    private final byte[] cursorKey;

    JdbcSafetyHistory(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            Function<RequestIdentity, DeviceDirectoryBatch> directory, Function<RequestIdentity, String> keys,
            Function<AuthorizedDeviceScope, Map<String, Map<String, Object>>> currentDevices,
            Clock clock, byte[] configuredCursorKey) {
        this.jdbc = jdbc; this.transactions = transactions; this.mapper = mapper;
        this.directory = directory; this.keys = keys; this.currentDevices = currentDevices; this.clock = clock;
        this.cursorKey = configuredCursorKey == null ? new byte[32] : configuredCursorKey.clone();
        if (configuredCursorKey == null) new java.security.SecureRandom().nextBytes(cursorKey);
    }

    @Override public AuthorizedDeviceScope authorizedScope(RequestIdentity identity) {
        try { return access(identity).scope(); }
        catch (DataAccessException failure) { throw unavailable(); }
    }

    @Override public Mono<ObservationResponse> read(ObservationRequest request) {
        return Mono.defer(() -> {
            try {
                if (request.operation() != ObservationOperationEnum.SAFETY_EVENTS && request.operation() != ObservationOperationEnum.SAFETY_EVENT)
                    throw new BusinessException("INVALID_SAFETY_QUERY", "安全台账读取类型无效", 400);
                Access access = access(request.identity());
                String type = request.operation() == ObservationOperationEnum.SAFETY_EVENT
                        ? type(request.parameters().get("ledger")) : request.parameters().getOrDefault("type", "gas");
                if (!TYPES.contains(type)) throw new BusinessException("INVALID_SAFETY_QUERY", "台账类别无效", 400);
                String key = dataKey(access, type);
                Progress before = progress(key);
                if (hasDevices(access, type) && (before == null || before.fullSuccess() == null)) throw preparing();
                Map<String, Map<String, Object>> deviceMetadata = currentDevices.apply(access.scope());
                IotAdapter localScope = new IotAdapter() {
                    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity id) {
                        return Mono.fromCallable(() -> authorizedScope(id));
                    }
                };
                ObservationAdapter local = ObservationAdapterFactory.safetyLedger(
                        page -> Mono.fromCallable(() -> page(key, page.parameters(), request.parameters())),
                        localScope, (id, hub) -> Mono.just(deviceMetadata.getOrDefault(hub, Map.of())), cursorKey, clock);
                return local.read(request).map(response -> {
                    Map<String, Object> payload = new LinkedHashMap<>(response.payload());
                    if (request.operation() == ObservationOperationEnum.SAFETY_EVENTS) {
                        payload.put("availableWindow", bounds(key));
                        if (payload.get("window") instanceof Map<?, ?> window) {
                            List<Object> args = new ArrayList<>(List.of(key,
                                    Timestamp.from(parse(window.get("from").toString())), Timestamp.from(parse(window.get("to").toString()))));
                            String filter = "";
                            if (request.parameters().containsKey("deviceId")) {
                                filter = " AND device_id=?";
                                args.add(request.parameters().get("deviceId"));
                            }
                            payload.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM local_safety_events WHERE scope_key=? "
                                    + "AND occurred_at>=? AND occurred_at<=?" + filter, Long.class, args.toArray()));
                        }
                    }
                    Access current = access(request.identity());
                    Progress latest = progress(key);
                    if (!sameAccess(access, current) || version(before) != version(latest)) throw preparing();
                    if (latest != null) {
                        Map<String, Object> cache = new LinkedHashMap<>();
                        cache.put("updatedAt", latest.success() == null ? null : latest.success().toString());
                        cache.put("status", latest.failed() || latest.success() == null || !clock.instant().isBefore(latest.success().plusSeconds(60)) ? "stale" : "fresh");
                        if (latest.failed()) cache.put("lastError", "安全台账同步未完成");
                        payload.put("cache", cache);
                    }
                    return ObservationResponse.available(payload);
                }).onErrorMap(DataAccessException.class, failure -> unavailable());
            } catch (DataAccessException failure) { return Mono.error(unavailable()); }
            catch (RuntimeException failure) { return Mono.error(failure); }
        });
    }

    private ObservationResponse page(String key, Map<String, String> page, Map<String, String> query) {
        List<Object> args = new ArrayList<>();
        args.add(key);
        StringBuilder where = new StringBuilder("scope_key=?");
        if (page.containsKey("deviceId")) { where.append(" AND device_id=?"); args.add(page.get("deviceId")); }
        if (query.containsKey("id")) { where.append(" AND event_id=?"); args.add(query.get("id")); }
        if (query.containsKey("from")) { where.append(" AND occurred_at>=?"); args.add(Timestamp.from(parse(query.get("from")))); }
        if (query.containsKey("to")) { where.append(" AND occurred_at<=?"); args.add(Timestamp.from(parse(query.get("to")))); }
        boolean latest = "latest".equals(query.get("selection"));
        int offset = Integer.parseInt(page.getOrDefault("page", "0")) * 100;
        args.add(latest ? 1 : 101); args.add(offset);
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM local_safety_events WHERE " + where
                + " ORDER BY order_key DESC LIMIT ? OFFSET ?", JdbcSafetyHistory::record, args.toArray());
        boolean last = latest || rows.size() <= 100;
        return ObservationResponse.available(Map.of("items", rows.subList(0, Math.min(rows.size(), 100)), "last", last));
    }

    private Map<String, Object> bounds(String key) {
        return jdbc.queryForObject("SELECT MIN(occurred_at) AS first_at,MAX(occurred_at) AS last_at FROM local_safety_events WHERE scope_key=?",
                (rs, row) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Instant first = instant(rs, "first_at"), last = instant(rs, "last_at");
                    result.put("from", first == null ? null : first.toString());
                    result.put("to", last == null ? null : last.toString());
                    return result;
                }, key);
    }

    private Access access(RequestIdentity identity) {
        directory.apply(identity); // Includes the current principal's credential and authorization barriers.
        String key = keys.apply(identity);
        List<Access> rows = jdbc.query("SELECT s.gateway_instance,s.tenant_code,s.source,s.credential_generation,s.batch_version,g.devices_json "
                + "FROM local_sync_scopes s JOIN local_safety_grants g ON g.scope_key=s.scope_key "
                + "WHERE s.scope_key=? AND s.published_generation=s.credential_generation AND s.denial_status=0 "
                + "AND g.credential_generation=s.credential_generation AND g.directory_version=s.batch_version", (rs, row) -> {
                    List<AuthorizedDevice> devices;
                    try {
                        JsonNode json = mapper.readTree(rs.getString("devices_json"));
                        if (json.isTextual()) json = mapper.readTree(json.asText()); // H2 JSON binding; MySQL returns the array directly.
                        devices = Arrays.asList(mapper.treeToValue(json, AuthorizedDevice[].class));
                    } catch (java.io.IOException invalid) { throw unavailable(); }
                    String realm = LocalDataKeys.observations(rs.getString("gateway_instance"), rs.getString("tenant_code"), rs.getString("source"));
                    return new Access(key, rs.getString("credential_generation"), rs.getLong("batch_version"), realm,
                            AuthorizedDeviceScope.of(identity, identity.source(), devices));
                }, key);
        if (rows.isEmpty()) throw preparing();
        return rows.getFirst();
    }

    void publishGrants(RequestIdentity identity, AuthorizedDeviceScope grant, Runnable authorize) {
        if (grant == null || !identity.equals(grant.identity()) || grant.source() != identity.source()) throw unavailable();
        String key = keys.apply(identity);
        String json;
        try { json = mapper.writeValueAsString(grant.devices().stream().filter(d -> "safety".equals(d.kind())).toList()); }
        catch (java.io.IOException invalid) { throw unavailable(); }
        transactions.executeWithoutResult(tx -> {
            authorize.run();
            var scopes = jdbc.queryForList("SELECT credential_generation,batch_version FROM local_sync_scopes WHERE scope_key=? "
                    + "AND published_generation=credential_generation AND denial_status=0 FOR UPDATE", key);
            if (scopes.isEmpty()) throw preparing();
            Map<String, Object> scope = scopes.getFirst();
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO local_safety_grants(scope_key,credential_generation,directory_version,devices_json,create_time,update_time) "
                    + "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE credential_generation=VALUES(credential_generation),"
                    + "directory_version=VALUES(directory_version),devices_json=VALUES(devices_json),update_time=VALUES(update_time)",
                    key, scope.get("credential_generation"), scope.get("batch_version"), json, now, now);
            authorize.run();
        });
    }

    /** Only the existing background directory cadence calls this method. No read invokes it. */
    void synchronize(RequestIdentity identity, ObservationAdapter source, boolean full, Runnable verify, Runnable authorize) {
        Access access = access(identity);
        for (String type : TYPES) {
            if (!hasDevices(access, type)) continue;
            String key = dataKey(access, type), run = UUID.randomUUID().toString();
            Instant attempt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (!claim(access, key, full, attempt)) continue;
            try {
                verify.run();
                authorize.run();
                Progress previous = progress(key);
                collect(source, identity, access, type, key, run, full ? null : previous.coveredUntil());
                verify.run();
                authorize.run();
                transactions.executeWithoutResult(tx -> {
                    lock(access);
                    if (!sameAccess(access, access(identity))) throw preparing();
                    if (jdbc.queryForList("SELECT id FROM local_safety_sync WHERE scope_key=? AND attempt_at=? FOR UPDATE", key, Timestamp.from(attempt)).isEmpty()) throw preparing();
                    authorize.run();
                    Timestamp now = Timestamp.from(clock.instant());
                    // Event timestamps describe its last revision; stream progress records every successful collection.
                    // 512 utf8mb4 characters fit 2048 bytes; H2 otherwise defaults BINARY casts to one byte.
                    jdbc.update("INSERT INTO local_safety_events(" + FIELDS + ",synchronized_at,create_time,update_time) SELECT " + FIELDS
                            + ",?,?,? FROM local_safety_batches b WHERE b.run_id=? AND b.scope_key=? AND NOT EXISTS ("
                            + "SELECT 1 FROM local_safety_events e WHERE e.scope_key=b.scope_key AND e.event_key=b.event_key "
                            + "AND ((OCTET_LENGTH(e.recorded_device_name)=OCTET_LENGTH(b.recorded_device_name) "
                            + "AND CAST(e.recorded_device_name AS BINARY(2048))=CAST(b.recorded_device_name AS BINARY(2048))) "
                            + "OR (e.recorded_device_name IS NULL AND b.recorded_device_name IS NULL)) "
                            + "AND (e.alarm=b.alarm OR (e.alarm IS NULL AND b.alarm IS NULL)) "
                            + "AND (e.report=b.report OR (e.report IS NULL AND b.report IS NULL))) ON DUPLICATE KEY UPDATE "
                            + "recorded_device_name=VALUES(recorded_device_name),alarm=VALUES(alarm),report=VALUES(report),synchronized_at=VALUES(synchronized_at),update_time=VALUES(update_time)",
                            now, now, now, run, key);
                    jdbc.update("UPDATE local_safety_sync SET publication_version=publication_version+1,success_at=?,covered_until=?,"
                            + (full ? "full_success_at=?,full_next_due_at=?," : "") + "failure_code=NULL,next_due_at=?,update_time=? WHERE scope_key=?",
                            full ? new Object[]{now, Timestamp.from(attempt), now, Timestamp.from(JdbcEnergyHistory.nextNight(clock.instant())), Timestamp.from(clock.instant().plusSeconds(60)), now, key}
                                    : new Object[]{now, Timestamp.from(attempt), Timestamp.from(clock.instant().plusSeconds(60)), now, key});
                    authorize.run();
                });
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE local_safety_sync SET failure_code='SAFETY_HISTORY_SYNC_UNAVAILABLE',next_due_at=?,update_time=? WHERE scope_key=? AND attempt_at=?",
                        Timestamp.from(clock.instant().plusSeconds(60)), Timestamp.from(clock.instant()), key, Timestamp.from(attempt));
                if (failure instanceof GatewayAuthorizationException) throw failure;
                if (failure instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)) throw failure;
            } finally { jdbc.update("DELETE FROM local_safety_batches WHERE run_id=?", run); }
        }
    }

    private boolean claim(Access access, String key, boolean full, Instant attempt) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            lock(access);
            Timestamp now = Timestamp.from(attempt);
            jdbc.update("INSERT IGNORE INTO local_safety_sync(scope_key,next_due_at,full_next_due_at,create_time,update_time) VALUES (?,?,?,?,?)", key, now, now, now, now);
            var previous = jdbc.queryForMap("SELECT * FROM local_safety_sync WHERE scope_key=? FOR UPDATE", key);
            if (!full && previous.get("full_success_at") == null) return false;
            int count = jdbc.update("UPDATE local_safety_sync SET attempt_at=?,next_due_at=?,update_time=? WHERE scope_key=? AND next_due_at<=?"
                    + (full ? " AND full_next_due_at<=?" : ""), full
                    ? new Object[]{now, Timestamp.from(attempt.plusSeconds(900)), now, key, now, now}
                    : new Object[]{now, Timestamp.from(attempt.plusSeconds(900)), now, key, now});
            if (count == 0) return false;
            jdbc.update("DELETE FROM local_safety_batches WHERE scope_key=?", key);
            return true;
        }));
    }

    private void collect(ObservationAdapter source, RequestIdentity identity, Access access, String type, String key, String run, Instant cutoff) {
        HashSet<String> pages = new HashSet<>();
        Long expected = null;
        long scanned = 0;
        Instant previousOldest = null;
        var authorizedIds = access.scope().devices().stream().filter(d -> type.equals(subtype(d.name())))
                .map(AuthorizedDevice::id).collect(java.util.stream.Collectors.toSet());
        for (int page = 0; ; page = Math.incrementExact(page)) {
            ObservationResponse response = source.read(new ObservationRequest(identity, ObservationOperationEnum.SAFETY_EVENTS,
                    Map.of("ledger", ledger(type), "page", Integer.toString(page), "limit", "100"))).block();
            if (response == null || response.status() != ObservationStatusEnum.AVAILABLE || !(response.payload().get("items") instanceof List<?> items)
                    || !(response.payload().get("last") instanceof Boolean last)) throw unavailable();
            Object total = response.payload().get("total");
            if (total instanceof Number number) {
                if (expected != null && expected != number.longValue()) throw unavailable();
                expected = number.longValue();
            }
            int rawCount = response.payload().get("scanned") instanceof Number count ? count.intValue() : items.size();
            if (rawCount < items.size() || rawCount > 100 || !last && rawCount == 0) throw unavailable();
            scanned += rawCount;
            if (!items.isEmpty() && !pages.add(LocalDataKeys.hash(items.toString()))) throw unavailable();
            Instant newest = null, oldest = null;
            List<Object[]> batch = new ArrayList<>();
            Timestamp now = Timestamp.from(clock.instant());
            for (Object value : items) {
                if (!(value instanceof Map<?, ?> row)) throw unavailable();
                Instant at = parse(row.get("occurredAt").toString());
                if (newest == null || at.isAfter(newest)) newest = at;
                if (oldest == null || at.isBefore(oldest)) oldest = at;
                String device = row.get("deviceId").toString();
                if (!authorizedIds.contains(device)) continue;
                String id = row.get("eventId").toString(), ledger = row.get("ledger").toString();
                if (!ledger(type).equals(ledger)) throw unavailable();
                String eventKey = LocalDataKeys.hash(ledger + "\u0000" + device + "\u0000" + id + "\u0000" + at);
                String order = String.format("%013d", at.toEpochMilli()) + ":" + (id.matches("\\d+") ? String.format("%06d", id.length()) + ":" + id : id) + ":" + device;
                batch.add(new Object[]{run, key, eventKey, ledger, id, device, row.get("deviceName"), Timestamp.from(at), order, row.get("alarm"), row.get("report"), now, now});
            }
            if (previousOldest != null && newest != null && newest.isAfter(previousOldest)) throw unavailable();
            if (oldest != null) previousOldest = oldest;
            jdbc.batchUpdate("INSERT INTO local_safety_batches(run_id," + FIELDS + ",create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", batch);
            if (last) {
                if (expected != null && scanned != expected) throw unavailable();
                return;
            }
            if (cutoff != null && oldest != null && oldest.isBefore(cutoff)) return;
        }
    }

    private void lock(Access access) {
        if (jdbc.queryForList("SELECT id FROM local_sync_scopes WHERE scope_key=? AND credential_generation=? AND published_generation=? AND batch_version=? AND denial_status=0 FOR UPDATE",
                access.key(), access.generation(), access.generation(), access.version()).isEmpty()) throw preparing();
    }
    private Progress progress(String key) {
        var rows = jdbc.query("SELECT * FROM local_safety_sync WHERE scope_key=?", (rs, row) -> new Progress(rs.getLong("publication_version"),
                instant(rs, "success_at"), instant(rs, "covered_until"), instant(rs, "full_success_at"), rs.getString("failure_code") != null), key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static boolean sameAccess(Access a, Access b) { return a.key().equals(b.key()) && a.generation().equals(b.generation()) && a.version() == b.version(); }
    private static long version(Progress progress) { return progress == null ? 0 : progress.version(); }
    private static boolean hasDevices(Access access, String type) { return access.scope().devices().stream().anyMatch(d -> type.equals(subtype(d.name()))); }
    private static String dataKey(Access access, String type) {
        String members = access.scope().devices().stream().filter(d -> type.equals(subtype(d.name()))).map(LocalDataKeys::observation).sorted().collect(java.util.stream.Collectors.joining("\u0000"));
        return LocalDataKeys.hash("SAFETY_FACTS_V1\u0000" + access.realm() + "\u0000" + type + "\u0000" + members);
    }
    private static String subtype(String name) { return name.contains("烟") ? "smoke" : name.contains("水浸") ? "water" : name.contains("燃气") ? "gas" : "unknown"; }
    private static String ledger(String type) { return "water".equals(type) ? "flooding" : type; }
    private static String type(String ledger) { return "flooding".equals(ledger) ? "water" : ledger; }
    private static Instant parse(String value) { try { return OffsetDateTime.parse(value).toInstant(); } catch (RuntimeException invalid) { throw new BusinessException("INVALID_SAFETY_QUERY", "告警时间须包含明确时区", 400); } }
    private static Instant instant(ResultSet rs, String column) throws SQLException { Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static Map<String, Object> record(ResultSet rs, int ignored) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ledger", rs.getString("ledger")); row.put("eventId", rs.getString("event_id")); row.put("deviceId", rs.getString("device_id"));
        row.put("deviceName", rs.getString("recorded_device_name")); row.put("occurredAt", instant(rs, "occurred_at").toString());
        row.put("alarm", rs.getObject("alarm")); row.put("report", rs.getObject("report"));
        return row;
    }
    private static BusinessException preparing() { return new BusinessException("SAFETY_HISTORY_PREPARING", "安全台账尚未完整同步，请稍后重试", 503); }
    private static BusinessException unavailable() { return new BusinessException("SAFETY_HISTORY_UNAVAILABLE", "本地安全台账暂不可用，请稍后重试", 503); }
    private record Access(String key, String generation, long version, String realm, AuthorizedDeviceScope scope) { }
    private record Progress(long version, Instant success, Instant coveredUntil, Instant fullSuccess, boolean failed) { }
}
