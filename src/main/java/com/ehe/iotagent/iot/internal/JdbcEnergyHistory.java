package com.ehe.iotagent.iot.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceDirectoryBatch;
import com.ehe.iotagent.iot.EnergyHistory;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.ObservationFailure;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.ObservationResponse;
import com.ehe.iotagent.iot.RequestIdentity;

/** Raw facts and complete-stream coverage. Staged pages never become ordinary readable facts. */
public final class JdbcEnergyHistory implements EnergyHistory {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<List<String>>> PATHS = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() { };
    // Update collection time last: every preceding CASE must compare the original retained row.
    private static final List<String> PUBLISHED_FIELDS = List.of("epf", "epz", "total_electric", "power_factor",
            "conflicted", "update_time", "synchronized_at");
    private static final String PUBLISH_SAMPLES = "INSERT INTO local_energy_samples(scope_key,sample_key,meter_key,source_meter_id,source_record_id,source_id_missing,read_time,epf,epz,total_electric,power_factor,"
            + "conflicted,credential_generation,synchronized_at,create_time,update_time) "
            + "SELECT scope_key,sample_key,meter_key,source_meter_id,source_record_id,source_id_missing,read_time,epf,epz,total_electric,power_factor,conflicted,?,?,?,? "
            + "FROM local_energy_sample_batches WHERE run_id=? AND scope_key=? ON DUPLICATE KEY UPDATE "
            + String.join(",", PUBLISHED_FIELDS.stream().map(field -> field
                    + "=CASE WHEN local_energy_samples.credential_generation=VALUES(credential_generation) AND local_energy_samples.synchronized_at>? "
                    + "THEN local_energy_samples." + field + " ELSE VALUES(" + field + ") END").toList())
            + ",credential_generation=VALUES(credential_generation)";
    // Stored observations are reusable facts; the requesting owner's grant remains the read gate.
    private static final String SOURCES = "sources AS (SELECT s.scope_key FROM local_sync_scopes v "
            + "JOIN local_sync_scopes s ON s.gateway_instance=v.gateway_instance AND s.tenant_code=v.tenant_code "
            + "AND s.source=v.source AND s.data_kind=v.data_kind WHERE v.scope_key=?)";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Function<RequestIdentity, DeviceDirectoryBatch> directory;
    private final Function<RequestIdentity, String> keys;
    private final Clock clock;

    public JdbcEnergyHistory(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            Function<RequestIdentity, DeviceDirectoryBatch> directory, Function<RequestIdentity, String> keys, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.directory = directory;
        this.keys = keys;
        this.clock = clock;
    }

    @Override public AuthorizedDeviceScope authorizedScope(RequestIdentity identity) {
        try { return grant(identity).scope(); }
        catch (DataAccessException failure) { throw unavailable(); }
    }

    @Override public Window window(RequestIdentity identity, List<String> meterIds, Instant from, Instant to, boolean includeLatest) {
        if (from == null || to == null || from.isAfter(to)) throw invalidWindow();
        try {
            Grant grant = grant(identity);
            List<String> ids = checkedIds(grant, meterIds);
            if (ids.isEmpty()) return new Window(List.of(), List.of(), cache(grant));
            // One statement holds the viewer's grant, shared coverage and latest published sample revisions.
            StringBuilder sql = new StringBuilder("WITH " + SOURCES + ", requested AS (SELECT meter_key,source_meter_id FROM local_energy_meters "
                    + "WHERE scope_key=? AND credential_generation=? AND grant_version=? AND source_meter_id IN (")
                    .append(String.join(",", Collections.nCopies(ids.size(), "?")))
                    .append(")), coverage AS (SELECT c.meter_key,MAX(c.synchronized_at) AS history_synced_at FROM local_energy_coverage c "
                            + "JOIN sources k ON k.scope_key=c.scope_key JOIN requested r ON r.meter_key=c.meter_key GROUP BY c.meter_key), "
                            + "edges AS (SELECT s.meter_key,MAX(CASE WHEN s.read_time<? THEN s.read_time END) AS left_at");
            List<Object> parameters = new ArrayList<>(List.of(grant.key(), grant.key(), grant.generation(), grant.version()));
            parameters.addAll(ids);
            parameters.add(Timestamp.from(from));
            if (includeLatest) sql.append(",MAX(s.read_time) AS latest_at");
            sql.append(" FROM requested r JOIN local_energy_samples s ON s.meter_key=r.meter_key "
                    + "JOIN sources k ON k.scope_key=s.scope_key");
            if (!includeLatest) {
                sql.append(" WHERE s.read_time<?");
                parameters.add(Timestamp.from(from));
            }
            sql.append(" GROUP BY s.meter_key), facts AS (SELECT s.* FROM requested r JOIN local_energy_samples s ON s.meter_key=r.meter_key "
                    + "JOIN sources k ON k.scope_key=s.scope_key WHERE s.read_time>=? AND s.read_time<=?");
            parameters.addAll(List.of(Timestamp.from(from), Timestamp.from(to)));
            appendEdgeQuery(sql, "left_at");
            if (includeLatest) appendEdgeQuery(sql, "latest_at");
            sql.append("), revisions AS (SELECT f.*,ROW_NUMBER() OVER(PARTITION BY sample_key ORDER BY synchronized_at DESC,scope_key) AS revision_rank "
                    + "FROM facts f) SELECT r.source_meter_id AS requested_meter_id,c.history_synced_at,f.* FROM requested r "
                    + "LEFT JOIN coverage c ON c.meter_key=r.meter_key "
                    + "LEFT JOIN revisions f ON f.meter_key=r.meter_key AND f.revision_rank=1 ORDER BY r.source_meter_id,f.read_time,f.sample_key");
            Window view = jdbc.query(sql.toString(), rs -> {
                var missing = new LinkedHashSet<String>();
                Map<String, Sample> samples = new LinkedHashMap<>();
                Instant sharedSuccess = null;
                while (rs.next()) {
                    Instant covered = instant(rs, "history_synced_at");
                    if (covered == null) missing.add(rs.getString("requested_meter_id"));
                    if (covered != null && (sharedSuccess == null || covered.isAfter(sharedSuccess))) sharedSuccess = covered;
                    String sampleKey = rs.getString("sample_key");
                    if (sampleKey != null && !samples.containsKey(sampleKey)) samples.put(sampleKey, sample(rs, 0));
                }
                return new Window(new ArrayList<>(samples.values()), new ArrayList<>(missing), cache(grant, sharedSuccess));
            }, parameters.toArray());
            recheck(identity, grant);
            for (String missing : view.missingCoverageMeterIds()) request(grant, meterKey(missing), from, to);
            if (view.missingCoverageMeterIds().size() == ids.size()) throw preparing();
            return view;
        } catch (DataAccessException failure) { throw unavailable(); }
    }

    @Override public ObservationResponse read(ObservationRequest request) {
        if (request.operation() != ObservationOperationEnum.ENERGY_HISTORY) throw invalidWindow();
        try {
            Grant grant = grant(request.identity());
            Map<String, String> p = request.parameters();
            Instant from = time(p.getOrDefault("from", "1970-01-01T00:00:00Z"));
            Instant to = p.containsKey("to") ? time(p.get("to")) : clock.instant();
            int page = integer(p.getOrDefault("page", "0"), 0, Integer.MAX_VALUE);
            int size = integer(p.getOrDefault("size", "20"), 1, 500);
            if (from.isAfter(to)) throw invalidWindow();
            String meter = p.get("equipmentId");
            List<String> ids = meter == null || meter.isBlank()
                    ? grant.scope().devices().stream().map(AuthorizedDevice::id).toList() : checkedIds(grant, List.of(meter));
            Window view = window(request.identity(), ids, from, to, false);
            List<Sample> inRange = view.samples().stream().filter(sample -> !sample.readTime().isBefore(from) && !sample.readTime().isAfter(to))
                    .sorted(java.util.Comparator.comparing(Sample::readTime).reversed().thenComparing(Sample::identity)).toList();
            long offset = (long) page * size;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long index = offset; index < Math.min(offset + size, inRange.size()); index++) rows.add(publicSample(inRange.get((int) index)));
            // Runtime pages present their samples in ascending order; paging itself follows the source's newest-first order.
            Collections.reverse(rows);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "company"); body.put("timezone", "Asia/Shanghai");
            body.put("observedAt", inRange.isEmpty() ? null : inRange.getFirst().readTime().toString());
            AuthorizedDevice device = meter == null ? null : grant.scope().device(meter).orElse(null);
            body.put("meter", Map.of("id", device == null ? "" : device.id(), "name", device == null ? "" : device.name(),
                    "location", device == null ? "" : device.installAddress()));
            body.put("window", Map.of("from", from.toString(), "to", to.toString()));
            body.put("fields", List.of(field("epf", "反向有功累计示数（epf）"), field("epz", "正向有功累计示数（epz）"),
                    field("totalElectric", "平台总电量示数（totalElectric）")));
            body.put("multiplier", null); body.put("samples", rows); body.put("totalElements", inRange.size());
            boolean more = offset + size < inRange.size();
            boolean conflict = inRange.stream().anyMatch(Sample::conflicted);
            boolean complete = !more && view.missingCoverageMeterIds().isEmpty() && !conflict;
            body.put("coverage", Map.of("complete", complete, "returned", rows.size()));
            body.put("missingCoverageMeterIds", view.missingCoverageMeterIds());
            body.put("nextPage", more ? page + 1 : null);
            body.put("latestReadTime", inRange.isEmpty() ? null : inRange.getFirst().readTime().toString());
            body.put("latestSample", inRange.isEmpty() ? null : publicSample(inRange.getFirst()));
            body.put("cache", view.cache());
            recheck(request.identity(), grant);
            if (!complete) return ObservationResponse.partial(body, List.of(new ObservationFailure("energy",
                    more && view.missingCoverageMeterIds().isEmpty() && !conflict ? "HISTORY_PARTIAL" : "HISTORY_INCOMPLETE",
                    more ? "计量历史仅读取到当前页" : "部分电表尚未完整同步或存在来源冲突")), false);
            return inRange.isEmpty() ? ObservationResponse.empty(body) : ObservationResponse.available(body);
        } catch (DataAccessException failure) { throw unavailable(); }
    }

    private static void appendEdgeQuery(StringBuilder sql, String boundary) {
        // Group boundaries once, then retain every source record at that timestamp.
        sql.append(" UNION ALL SELECT s.* FROM edges e JOIN local_energy_samples s ON s.meter_key=e.meter_key AND s.read_time=e.")
                .append(boundary).append(" JOIN sources k ON k.scope_key=s.scope_key");
    }

    private Grant grant(RequestIdentity identity) {
        if (identity.source() != IotSourceEnum.COMPANY) throw new BusinessException("ENERGY_SOURCE_UNAVAILABLE", "计量观察仅支持公司来源", 404);
        directory.apply(identity); // Registers credentials and checks local trust; never collects customer data.
        String key = keys.apply(identity);
        return jdbc.query("SELECT e.*,m.source_meter_id,m.meter_name,m.install_address,m.location_paths_json,m.metadata_json "
                + "FROM local_energy_sync e JOIN local_sync_scopes s ON s.scope_key=e.scope_key "
                + "LEFT JOIN local_energy_meters m ON m.scope_key=e.scope_key AND m.credential_generation=e.credential_generation AND m.grant_version=e.grant_version "
                + "WHERE e.scope_key=? AND e.credential_generation=s.credential_generation AND s.published_generation=s.credential_generation ORDER BY m.source_meter_id",
                rs -> {
                    if (!rs.next() || rs.getLong("grant_version") == 0) throw preparing();
                    String generation = rs.getString("credential_generation");
                    long version = rs.getLong("grant_version");
                    Instant current = instant(rs, "current_success_at"), full = instant(rs, "full_success_at");
                    String failure = rs.getString("current_failure_code") == null
                            ? rs.getString("full_failure_code") : rs.getString("current_failure_code");
                    List<AuthorizedDevice> meters = new ArrayList<>();
                    do {
                        if (rs.getString("source_meter_id") == null) continue;
                        try {
                            meters.add(new AuthorizedDevice(rs.getString("source_meter_id"), "energy", rs.getString("meter_name"),
                                    rs.getString("install_address"), true, null, null, null, null,
                                    mapper.readValue(rs.getString("location_paths_json"), PATHS), List.of(),
                                    mapper.readValue(rs.getString("metadata_json"), METADATA)));
                        } catch (java.io.IOException invalid) { throw unavailable(); }
                    } while (rs.next());
                    return new Grant(key, generation, version, AuthorizedDeviceScope.of(identity, identity.source(), meters), current, full, failure);
                }, key);
    }

    private void recheck(RequestIdentity identity, Grant grant) {
        Grant current = grant(identity);
        if (!current.generation().equals(grant.generation()) || current.version() != grant.version()) {
            checkedIds(current, grant.scope().devices().stream().map(AuthorizedDevice::id).toList());
            throw new BusinessException("ENERGY_AUTHORIZATION_CHANGED", "电表授权已变化，请重新读取", 409);
        }
    }

    private static List<String> checkedIds(Grant grant, List<String> ids) {
        if (ids == null || new LinkedHashSet<>(ids).size() != ids.size()) throw invalidWindow();
        for (String id : ids) if (id == null || grant.scope().device(id).isEmpty())
            throw new BusinessException("DEVICE_OUTSIDE_SCOPE", "电表不在当前授权范围内", 403);
        return ids;
    }

    private void request(Grant grant, String meter, Instant from, Instant to) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT IGNORE INTO local_energy_requests(scope_key,request_key,meter_key,range_from,range_to,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                grant.key(), LocalDataKeys.hash(meter + "\u0000" + from + "\u0000" + to), meter, Timestamp.from(from), Timestamp.from(to), now, now);
        // Grant publication schedules full coverage; a repeated read must not erase failure backoff.
    }

    private Map<String, Object> cache(Grant grant) {
        return cache(grant, null);
    }

    private Map<String, Object> cache(Grant grant, Instant sharedSuccess) {
        Instant success = grant.currentSuccess();
        if (success == null || grant.fullSuccess() != null && grant.fullSuccess().isAfter(success)) success = grant.fullSuccess();
        if (sharedSuccess != null && (success == null || sharedSuccess.isAfter(success))) success = sharedSuccess;
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("status", success == null || grant.failure() != null || !clock.instant().isBefore(success.plusSeconds(60)) ? "stale" : "fresh");
        cache.put("updatedAt", success == null ? null : success.toString());
        return cache;
    }

    /** Called only after a separate energy-scope source request, under the shared credential barrier. */
    public void publishGrants(String key, String generation, AuthorizedDeviceScope scope, Runnable authorize) {
        transactions.executeWithoutResult(tx -> {
            lockScope(key, generation); authorize.run();
            Timestamp now = Timestamp.from(clock.instant());
            jdbc.update("INSERT IGNORE INTO local_energy_sync(scope_key,credential_generation,full_next_due_at,create_time,update_time) VALUES (?,?,?,?,?)",
                    key, generation, now, now, now);
            List<Map<String, Object>> progress = jdbc.queryForList("SELECT credential_generation,grant_version FROM local_energy_sync WHERE scope_key=? FOR UPDATE", key);
            Map<String, Object> previous = progress.getFirst();
            boolean rotated = !generation.equals(previous.get("credential_generation"));
            Map<String, StoredMeter> previousMeters = new LinkedHashMap<>();
            jdbc.query("SELECT * FROM local_energy_meters WHERE scope_key=?",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        try {
                            previousMeters.put(rs.getString("source_meter_id"), new StoredMeter(rs.getString("meter_key"),
                                    new MeterBody(rs.getString("meter_name"), rs.getString("install_address"),
                                            mapper.readValue(rs.getString("location_paths_json"), PATHS),
                                            mapper.readValue(rs.getString("metadata_json"), METADATA)),
                                    rs.getString("credential_generation"), rs.getLong("grant_version")));
                        } catch (java.io.IOException invalid) { throw unavailable(); }
                    }, key);
            var oldIds = new LinkedHashSet<>(previousMeters.keySet());
            var nextIds = new LinkedHashSet<>(scope.devices().stream().map(AuthorizedDevice::id).toList());
            boolean membershipChanged = !nextIds.equals(oldIds);
            if (nextIds.size() != scope.devices().size() || scope.devices().stream().anyMatch(device -> !"energy".equals(device.kind())))
                throw new IllegalArgumentException("Invalid complete energy grant");
            long previousVersion = ((Number) previous.get("grant_version")).longValue();
            // Collection freshness is not an authorization change.
            long version = previousVersion == 0 || rotated || membershipChanged
                    ? previousVersion + 1 : previousVersion;
            List<Object[]> inserts = new ArrayList<>();
            List<Object[]> updates = new ArrayList<>();
            List<Object[]> versions = new ArrayList<>();
            for (AuthorizedDevice device : scope.devices()) {
                StoredMeter old = previousMeters.remove(device.id());
                String metadataJson = json(device.metadata());
                if (old == null) {
                    inserts.add(new Object[]{key,meterKey(device.id()),device.id(),device.name(),device.installAddress(),
                            json(device.locationPaths()),metadataJson,generation,version,now,now});
                } else {
                    MeterBody body;
                    try { body = new MeterBody(device.name(),device.installAddress(),device.locationPaths(),mapper.readValue(metadataJson,METADATA)); }
                    catch (java.io.IOException invalid) { throw unavailable(); }
                    if (!body.equals(old.body())) {
                        updates.add(new Object[]{device.name(),device.installAddress(),json(device.locationPaths()),metadataJson,
                                generation,version,now,key,old.key()});
                    } else if (!generation.equals(old.generation()) || version != old.version()) {
                        versions.add(new Object[]{generation,version,now,key,old.key()});
                    }
                }
            }
            if (!previousMeters.isEmpty()) jdbc.batchUpdate("DELETE FROM local_energy_meters WHERE scope_key=? AND meter_key=?",
                    previousMeters.values().stream().map(old -> new Object[]{key,old.key()}).toList());
            if (!updates.isEmpty()) jdbc.batchUpdate("UPDATE local_energy_meters SET meter_name=?,install_address=?,location_paths_json=?,metadata_json=?,"
                    + "credential_generation=?,grant_version=?,update_time=? WHERE scope_key=? AND meter_key=?", updates);
            if (!versions.isEmpty()) jdbc.batchUpdate("UPDATE local_energy_meters SET credential_generation=?,grant_version=?,update_time=? WHERE scope_key=? AND meter_key=?", versions);
            if (!inserts.isEmpty()) jdbc.batchUpdate("INSERT INTO local_energy_meters(scope_key,meter_key,source_meter_id,meter_name,install_address,location_paths_json,metadata_json,"
                    + "credential_generation,grant_version,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)", inserts);
            jdbc.update("UPDATE local_energy_sync SET credential_generation=?,grant_version=?,grants_published_at=?,update_time=? WHERE scope_key=?",
                    generation,version,now,now,key);
            if (membershipChanged) jdbc.update("UPDATE local_energy_sync SET current_attempt_at=NULL WHERE scope_key=?", key);
            // A verified credential renewal fences writers, not the owner's retained history.
            if (!oldIds.containsAll(nextIds)) jdbc.update("UPDATE local_energy_sync SET full_next_due_at=? WHERE scope_key=? AND full_state<>'RUNNING'",now,key);
            authorize.run();
        });
    }

    private record MeterBody(String name, String address, List<List<String>> paths, Map<String, Object> metadata) { }
    private record StoredMeter(String key, MeterBody body, String generation, long version) { }

    public boolean fullDue(String key, String generation) {
        return !jdbc.queryForList("SELECT id FROM local_energy_sync WHERE scope_key=? AND credential_generation=? AND full_next_due_at<=?", key,generation,Timestamp.from(clock.instant())).isEmpty();
    }

    public boolean begin(String key, String generation, boolean full, Instant attempt) {
        SharedCoverage shared = sharedCoverage(key, generation, full);
        if (!full && (shared == null || clock.instant().isBefore(shared.synchronizedAt().plusSeconds(60)))) return false;
        if (full) {
            if (shared != null && !shared.coveredUntil().isBefore(nextNight(attempt).minusSeconds(86400))) {
                jdbc.update("UPDATE local_energy_sync SET full_state='READY',full_success_at=?,full_covered_until=?,"
                                + "full_next_due_at=?,full_failure_code=NULL,update_time=? "
                                + "WHERE scope_key=? AND credential_generation=? AND full_next_due_at<=?",
                        Timestamp.from(shared.synchronizedAt()), Timestamp.from(shared.coveredUntil()), Timestamp.from(nextNight(attempt)),
                        Timestamp.from(clock.instant()), key, generation, Timestamp.from(attempt));
                return false;
            }
        }
        Instant deferred = equivalentAttempt(key, generation, full);
        if (deferred != null) {
            if (full) jdbc.update("UPDATE local_energy_sync SET full_next_due_at=? WHERE scope_key=? AND credential_generation=?",
                    Timestamp.from(deferred), key, generation);
            return false;
        }
        String next = full ? ",full_state='RUNNING',full_next_due_at=?" : "";
        List<Object> args = new ArrayList<>(); args.add(Timestamp.from(attempt));
        if (full) args.add(Timestamp.from(nextNight(attempt)));
        args.add(Timestamp.from(attempt)); args.add(key); args.add(generation);
        if (full) args.add(Timestamp.from(attempt));
        return jdbc.update("UPDATE local_energy_sync SET " + (full ? "full_attempt_at" : "current_attempt_at") + "=?" + next
                + ",update_time=? WHERE scope_key=? AND credential_generation=?" + (full ? " AND full_next_due_at<=?" : ""),args.toArray()) == 1;
    }

    /** Equivalent grants share a stream attempt, including its failure cooldown; partial grants stay distinct. */
    private Instant equivalentAttempt(String key, String generation, boolean full) {
        String column = full ? "full_attempt_at" : "current_attempt_at";
        String condition = full ? "(e.full_attempt_at>? OR (e.full_failure_code IS NOT NULL AND e.full_next_due_at>?)) AND e.scope_key<>?"
                : "e.current_attempt_at>?";
        String sql = "WITH " + SOURCES + ", requested AS (SELECT meter_key FROM local_energy_meters WHERE scope_key=? AND credential_generation=?) "
                + "SELECT e." + column + ",e.full_failure_code,e.full_next_due_at FROM local_energy_sync e JOIN sources s ON s.scope_key=e.scope_key WHERE " + condition
                + " AND NOT EXISTS (SELECT 1 FROM requested r WHERE NOT EXISTS (SELECT 1 FROM local_energy_meters m "
                + "WHERE m.scope_key=e.scope_key AND m.credential_generation=e.credential_generation AND m.meter_key=r.meter_key)) "
                + "AND NOT EXISTS (SELECT 1 FROM local_energy_meters m WHERE m.scope_key=e.scope_key AND m.credential_generation=e.credential_generation "
                + "AND NOT EXISTS (SELECT 1 FROM requested r WHERE r.meter_key=m.meter_key)) ORDER BY e." + column + " DESC LIMIT 1";
        List<Object> parameters = new ArrayList<>(List.of(key, key, generation, Timestamp.from(clock.instant().minusSeconds(60))));
        if (full) { parameters.add(Timestamp.from(clock.instant())); parameters.add(key); }
        List<Instant> attempts = jdbc.query(sql, (rs, row) -> {
            Instant due = instant(rs, column).plusSeconds(60);
            Instant retry = instant(rs, "full_next_due_at");
            return full && rs.getString("full_failure_code") != null && retry != null && retry.isAfter(due) ? retry : due;
        }, parameters.toArray());
        return attempts.isEmpty() ? null : attempts.getFirst();
    }

    public void stage(String run, String key, List<Sample> samples) {
        Timestamp now = Timestamp.from(clock.instant());
        List<Object[]> rows = new ArrayList<>(samples.size());
        for (Sample sample : samples) rows.add(new Object[]{run,key,sampleKey(sample),meterKey(sample.meterId()),sample.meterId(),sample.sourceId(),sample.sourceIdMissing(),
                Timestamp.from(sample.readTime()),decimal(sample.epf()),decimal(sample.epz()),decimal(sample.totalElectric()),decimal(sample.powerFactor()),sample.conflicted(),now,now});
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO local_energy_sample_batches(run_id,scope_key,sample_key,meter_key,source_meter_id,source_record_id,source_id_missing,read_time,"
                + "epf,epz,total_electric,power_factor,conflicted,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE conflicted=TRUE,update_time=VALUES(update_time)",rows);
    }

    public void publish(String run, String key, String generation, List<String> meters, boolean full, Instant attempt, Runnable authorize) {
        transactions.executeWithoutResult(tx -> {
            lockScope(key,generation); authorize.run();
            if (jdbc.queryForList("SELECT id FROM local_energy_sync WHERE scope_key=? AND credential_generation=? AND "
                    + (full ? "full_attempt_at" : "current_attempt_at") + "=? FOR UPDATE", key,generation,Timestamp.from(attempt)).isEmpty()) return;
            if (full) {
                List<String> current = jdbc.query("SELECT source_meter_id FROM local_energy_meters WHERE scope_key=? AND credential_generation=? ORDER BY source_meter_id",
                        (rs,row) -> rs.getString(1),key,generation);
                if (!current.equals(meters.stream().sorted().toList())) throw preparing();
            }
            Timestamp now = Timestamp.from(clock.instant());
            Object[] parameters = new Object[6 + PUBLISHED_FIELDS.size()];
            parameters[0]=generation;parameters[1]=now;parameters[2]=now;parameters[3]=now;parameters[4]=run;parameters[5]=key;
            java.util.Arrays.fill(parameters,6,parameters.length,Timestamp.from(attempt));
            jdbc.update(PUBLISH_SAMPLES,parameters);
            if (full) {
                List<Object[]> coverage = new ArrayList<>();
                for (String meter : meters) coverage.add(new Object[]{key,meterKey(meter),generation,Timestamp.from(attempt),now,now,now});
                if (!coverage.isEmpty()) jdbc.batchUpdate("INSERT INTO local_energy_coverage(scope_key,meter_key,credential_generation,covered_until,synchronized_at,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE credential_generation=VALUES(credential_generation),covered_until=VALUES(covered_until),"
                        + "synchronized_at=VALUES(synchronized_at),update_time=VALUES(update_time)",coverage);
                jdbc.update("UPDATE local_energy_sync SET full_state='READY',full_success_at=?,full_covered_until=?,full_failure_code=NULL,update_time=? WHERE scope_key=?",now,Timestamp.from(attempt),now,key);
                jdbc.update("UPDATE local_energy_requests SET state='READY',update_time=? WHERE scope_key=? AND range_to<=?",now,key,Timestamp.from(attempt));
            } else jdbc.update("UPDATE local_energy_sync SET current_success_at=?,current_failure_code=NULL,update_time=? WHERE scope_key=?",now,now,key);
            authorize.run();
        });
    }

    public Instant currentCutoff(String key, String generation) {
        SharedCoverage shared = sharedCoverage(key, generation, false);
        return shared == null ? null : shared.coveredUntil();
    }

    private SharedCoverage sharedCoverage(String key, String generation, boolean otherOwnersOnly) {
        String sql = "WITH " + SOURCES + ", coverage AS (SELECT c.meter_key,MAX(c.covered_until) AS covered_until,"
                + "MAX(CASE WHEN e.current_success_at>c.synchronized_at THEN e.current_success_at ELSE c.synchronized_at END) AS synchronized_at "
                + "FROM local_energy_coverage c JOIN sources k ON k.scope_key=c.scope_key JOIN local_energy_sync e ON e.scope_key=c.scope_key "
                + (otherOwnersOnly ? "WHERE c.scope_key<>? " : "")
                + "GROUP BY c.meter_key) SELECT COUNT(*) AS requested_count,COUNT(c.meter_key) AS covered_count,"
                + "MIN(c.covered_until) AS covered_until,MIN(c.synchronized_at) AS synchronized_at "
                + "FROM local_energy_meters m LEFT JOIN coverage c ON c.meter_key=m.meter_key "
                + "WHERE m.scope_key=? AND m.credential_generation=?";
        List<Object> parameters = new ArrayList<>();
        parameters.add(key);
        if (otherOwnersOnly) parameters.add(key);
        parameters.add(key);
        parameters.add(generation);
        return jdbc.query(sql, rs -> {
            if (!rs.next() || rs.getLong("requested_count") == 0
                    || rs.getLong("requested_count") != rs.getLong("covered_count")) return null;
            return new SharedCoverage(instant(rs, "covered_until"), instant(rs, "synchronized_at"));
        }, parameters.toArray());
    }

    private record SharedCoverage(Instant coveredUntil, Instant synchronizedAt) { }

    public void failed(String key, String generation, boolean full, Instant attempt) {
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp started = Timestamp.from(attempt);
        if (full) jdbc.update("UPDATE local_energy_sync SET full_attempt_at=?,full_state='FAILED',full_failure_code='ENERGY_HISTORY_SYNC_UNAVAILABLE',full_next_due_at=?,update_time=? "
                + "WHERE scope_key=? AND credential_generation=? AND (full_attempt_at IS NULL OR full_attempt_at<=?)",
                started,Timestamp.from(clock.instant().plusSeconds(60)),now,key,generation,started);
        else jdbc.update("UPDATE local_energy_sync SET current_attempt_at=?,current_failure_code='ENERGY_CURRENT_SYNC_UNAVAILABLE',update_time=? "
                + "WHERE scope_key=? AND credential_generation=? AND (current_attempt_at IS NULL OR current_attempt_at<=?)",
                started,now,key,generation,started);
    }

    public void discard(String run) { jdbc.update("DELETE FROM local_energy_sample_batches WHERE run_id=?",run); }

    private void lockScope(String key, String generation) {
        if (jdbc.queryForList("SELECT id FROM local_sync_scopes WHERE scope_key=? AND credential_generation=? AND published_generation=? FOR UPDATE",key,generation,generation).isEmpty()) throw preparing();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid meter metadata",failure); }
    }
    static Instant nextNight(Instant now) {
        var local = now.atZone(SHANGHAI);
        var next = local.toLocalDate().atTime(3,0).atZone(SHANGHAI);
        return (next.toInstant().isAfter(now) ? next : next.plusDays(1)).toInstant();
    }
    static String meterKey(String id) { return LocalDataKeys.hash(id); }
    static String sampleKey(Sample sample) { return LocalDataKeys.hash(sample.meterId()+"\u0000"+sample.identity()+"\u0000"+sample.readTime()); }
    static BigDecimal decimal(Object value) {
        if (value == null) return null;
        BigDecimal decimal = value instanceof BigDecimal number ? number : new BigDecimal(value.toString());
        decimal = decimal.setScale(30,RoundingMode.UNNECESSARY);
        if (decimal.precision()>65) throw new IllegalArgumentException("Energy decimal exceeds DECIMAL(65,30)");
        return decimal;
    }
    private static Sample sample(ResultSet rs,int row) throws SQLException {
        return new Sample(rs.getString("source_meter_id"),rs.getString("source_record_id"),rs.getBoolean("source_id_missing"),instant(rs,"read_time"),
                rs.getBigDecimal("epf"),rs.getBigDecimal("epz"),rs.getBigDecimal("total_electric"),rs.getBigDecimal("power_factor"),rs.getBoolean("conflicted"));
    }
    private static Map<String,Object> publicSample(Sample sample) {
        Map<String,Object> row = new LinkedHashMap<>(), values = new LinkedHashMap<>();
        row.put("id",sample.sourceIdMissing()?sample.readTime().toString():sample.sourceId());row.put("sourceIdMissing",sample.sourceIdMissing());
        row.put("equipmentId",sample.meterId());row.put("readTime",sample.readTime().toString());row.put("totalPowerFactor",sample.powerFactor());
        values.put("epf",sample.epf());values.put("epz",sample.epz());values.put("totalElectric",sample.totalElectric());row.put("values",values);row.put("conflicted",sample.conflicted());
        return row;
    }
    private static Map<String,Object> field(String key,String label) {
        Map<String,Object> field = new LinkedHashMap<>();field.put("key",key);field.put("label",label);field.put("unit",null);return field;
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static Instant time(String value) { try { return java.time.OffsetDateTime.parse(value).toInstant(); } catch(RuntimeException invalid) { throw invalidWindow(); } }
    private static int integer(String value,int min,int max) { try { int number=Integer.parseInt(value);if(number<min||number>max)throw invalidWindow();return number; }catch(NumberFormatException invalid){throw invalidWindow();} }
    private static BusinessException invalidWindow() { return new BusinessException("INVALID_HISTORY_WINDOW","计量历史窗口或设备引用无效",400); }
    private static BusinessException preparing() { return new BusinessException("ENERGY_HISTORY_PREPARING","计量历史尚未完整同步，请稍后重试",503); }
    private static BusinessException unavailable() { return new BusinessException("ENERGY_HISTORY_UNAVAILABLE","本地计量历史暂不可用",503); }
    private record Grant(String key,String generation,long version,AuthorizedDeviceScope scope,Instant currentSuccess,Instant fullSuccess,String failure) { }
}
