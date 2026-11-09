package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceDirectoryBatch;
import ltd.qianc.iotagent.iot.DeviceObservations;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.ObservationStatusEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Current observations and their publication checkpoint; never contacts a customer source. */
public final class JdbcDeviceObservations implements DeviceObservations {
    private record Field(String wire, String column) { }
    private static final List<Field> DECIMALS = List.of(
            new Field("temperature", "temperature"), new Field("targetTemperature", "target_temperature"),
            new Field("ia", "current_a"), new Field("ib", "current_b"), new Field("ic", "current_c"),
            new Field("totalActivePower", "active_power"), new Field("temp", "environment_temperature"),
            new Field("humidity", "humidity"), new Field("pm25", "pm25"), new Field("pm10", "pm10"), new Field("pm101", "pm101"));
    private static final List<Field> TEXT = List.of(new Field("power", "power_state"), new Field("mode", "mode_value"),
            new Field("fanSpeed", "fan_speed"), new Field("source", "observation_origin"));
    private static final TypeReference<Map<String, Object>> EXTENSIONS = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Function<RequestIdentity, DeviceDirectoryBatch> directory;
    private final Function<RequestIdentity, String> scopeKey;
    private final Clock clock;
    private final String upsert;

    public JdbcDeviceObservations(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            Function<RequestIdentity, DeviceDirectoryBatch> directory, Function<RequestIdentity, String> scopeKey, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.directory = directory;
        this.scopeKey = scopeKey;
        this.clock = clock;
        List<String> columns = new ArrayList<>(List.of("scope_key", "device_key", "source_device_id", "device_kind", "value_shape", "boolean_value"));
        DECIMALS.forEach(field -> columns.add(field.column()));
        TEXT.forEach(field -> columns.add(field.column()));
        columns.addAll(List.of("extensions_json", "observed_at", "synchronized_at", "credential_generation", "create_time", "update_time"));
        List<String> updates = columns.stream().filter(column -> !List.of("scope_key", "device_key", "create_time").contains(column))
                .map(column -> column + "=VALUES(" + column + ")").toList();
        upsert = "INSERT INTO local_device_states(" + String.join(",", columns) + ") VALUES ("
                + String.join(",", Collections.nCopies(columns.size(), "?")) + ") ON DUPLICATE KEY UPDATE " + String.join(",", updates);
    }

    /** Called only by the directory's independent current-data synchronization run. */
    public Instant begin(String key, String generation, long directoryVersion) {
        Instant attempt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return transactions.execute(tx -> {
            if (!current(key, generation, directoryVersion)) return null;
            Timestamp now = Timestamp.from(attempt);
            jdbc.update("INSERT INTO local_device_state_sync(scope_key,state,credential_generation,last_attempt_at,create_time,update_time) "
                            + "VALUES (?,'PREPARING',?,?,?,?) ON DUPLICATE KEY UPDATE state='PREPARING',"
                            + "last_success_at=CASE WHEN credential_generation=VALUES(credential_generation) THEN last_success_at ELSE NULL END,"
                            + "credential_generation=VALUES(credential_generation),"
                            + "last_attempt_at=VALUES(last_attempt_at),failure_code=NULL,update_time=VALUES(update_time)",
                    key, generation, now, now, now);
            return attempt;
        });
    }

    public void publish(String key, String generation, long directoryVersion, Instant attempt,
            AuthorizedDeviceScope scope, DeviceStateReadResult collected, Runnable checkAuthorization) {
        if (collected == null) throw unavailable();
        transactions.executeWithoutResult(tx -> {
            if (!current(key, generation, directoryVersion) || !currentAttempt(key, generation, attempt)) return;
            checkAuthorization.run();
            Map<String, Instant> prior = new LinkedHashMap<>();
            jdbc.query("SELECT device_key,observed_at FROM local_device_states WHERE scope_key=? AND credential_generation=?", rs -> {
                prior.put(rs.getString("device_key"), instant(rs, "observed_at"));
            }, key, generation);
            Instant committed = clock.instant().truncatedTo(ChronoUnit.MICROS);
            List<Object[]> rows = new ArrayList<>();
            for (AuthorizedDevice device : scope.devices()) {
                String deviceKey = LocalDataKeys.device(device);
                Object value = collected.states().get(device.id());
                Instant observed = value == null ? null
                        : parseTime(collected.observedAtByDevice().getOrDefault(device.id(), collected.observedAt()));
                Instant old = prior.get(deviceKey);
                // Older source samples cannot overwrite a newer retained observation or refresh its collection time.
                if (old != null && (observed == null || observed.isBefore(old))) continue;
                rows.add(values(key, deviceKey, generation, device, value, observed, committed));
            }
            if (!rows.isEmpty()) jdbc.batchUpdate(upsert, rows);
            checkAuthorization.run();
            jdbc.update("UPDATE local_device_state_sync SET state='READY',batch_version=batch_version+1,last_success_at=?,"
                            + "failure_code=NULL,update_time=? WHERE scope_key=? AND credential_generation=? AND last_attempt_at=?",
                    Timestamp.from(committed), Timestamp.from(committed), key, generation, Timestamp.from(attempt));
        });
    }

    public void failed(String key, String generation, Instant attempt) {
        if (attempt == null) return;
        jdbc.update("UPDATE local_device_state_sync SET state='FAILED',failure_code='DEVICE_STATE_SYNC_UNAVAILABLE',update_time=? "
                        + "WHERE scope_key=? AND credential_generation=? AND last_attempt_at=?",
                Timestamp.from(clock.instant()), key, generation, Timestamp.from(attempt));
    }

    @Override public DeviceStateReadResult read(RequestIdentity identity, List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty() || deviceIds.stream().anyMatch(id -> id == null || id.isBlank())
                || new LinkedHashSet<>(deviceIds).size() != deviceIds.size()) {
            throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        }
        try {
            DeviceDirectoryBatch batch = directory.apply(identity);
            List<AuthorizedDevice> requested = requested(batch.scope(), deviceIds);
            String key = scopeKey.apply(identity);
            String generation = publishedGeneration(key);
            List<Progress> progressRows = jdbc.query(
                    "SELECT state,last_success_at FROM local_device_state_sync WHERE scope_key=? AND credential_generation=?",
                    (rs, row) -> new Progress(rs.getString("state"), instant(rs, "last_success_at")), key, generation);
            if (progressRows.isEmpty()) throw preparing();
            Progress progress = progressRows.getFirst();
            Instant success = progress.success();
            if (success == null) {
                if ("FAILED".equals(progress.state())) throw unavailable();
                throw preparing();
            }
            List<Object> parameters = new ArrayList<>();
            parameters.add(key);
            parameters.add(generation);
            List<String> keys = requested.stream().map(LocalDataKeys::device).toList();
            parameters.addAll(keys);
            Map<String, Stored> records = new LinkedHashMap<>();
            jdbc.query("SELECT * FROM local_device_states WHERE scope_key=? AND credential_generation=? AND device_key IN ("
                            + String.join(",", Collections.nCopies(requested.size(), "?")) + ")", rs -> {
                        Stored stored = stored(rs);
                        records.put(rs.getString("device_key"), stored);
                    }, parameters.toArray());
            List<AuthorizedDevice> current = requested(directory.apply(identity).scope(), deviceIds);
            for (int index = 0; index < current.size(); index++) {
                if (!current.get(index).kind().equals(requested.get(index).kind())) throw outsideScope();
            }
            if (!generation.equals(publishedGeneration(key))) throw preparing();
            Map<String, Object> states = new LinkedHashMap<>();
            Map<String, String> times = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            Instant freshnessBoundary = clock.instant().minusSeconds(60);
            boolean stale = !"READY".equals(progress.state()) || !success.isAfter(freshnessBoundary);
            Instant oldest = null;
            for (int index = 0; index < requested.size(); index++) {
                AuthorizedDevice device = requested.get(index);
                Stored stored = records.get(keys.get(index));
                states.put(device.id(), stored == null ? null : stored.value());
                if (stored == null || stored.value() == null || stored.synchronizedAt().isBefore(success)) missing.add(device.id());
                if (stored == null) continue;
                if (stored.observed() != null) {
                    times.put(device.id(), stored.observed().toString());
                    if (oldest == null || stored.observed().isBefore(oldest)) oldest = stored.observed();
                    stale |= !stored.observed().isAfter(freshnessBoundary);
                }
                stale |= !stored.synchronizedAt().isAfter(freshnessBoundary) || stored.synchronizedAt().isBefore(success);
            }
            String observedAt = times.size() == requested.size() && oldest != null ? oldest.toString() : null;
            ObservationStatusEnum status = states.values().stream().allMatch(java.util.Objects::isNull)
                    ? ObservationStatusEnum.UNAVAILABLE
                    : !missing.isEmpty() || times.size() < requested.size() ? ObservationStatusEnum.PARTIAL : ObservationStatusEnum.AVAILABLE;
            return new DeviceStateReadResult(states, observedAt, videoBlocks(requested, states), times, status, missing,
                    Map.of("status", stale ? "stale" : "fresh", "updatedAt", success.toString()));
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    private String publishedGeneration(String key) {
        List<String> values = jdbc.query("SELECT credential_generation FROM local_sync_scopes "
                        + "WHERE scope_key=? AND credential_generation=published_generation",
                (rs, row) -> rs.getString(1), key);
        if (values.isEmpty()) throw preparing();
        return values.getFirst();
    }

    private boolean current(String key, String generation, long directoryVersion) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT credential_generation,published_generation,batch_version "
                + "FROM local_sync_scopes WHERE scope_key=? FOR UPDATE", key);
        if (rows.isEmpty()) return false;
        Map<String, Object> row = rows.getFirst();
        return generation.equals(row.get("credential_generation")) && generation.equals(row.get("published_generation"))
                && ((Number) row.get("batch_version")).longValue() == directoryVersion;
    }

    private boolean currentAttempt(String key, String generation, Instant attempt) {
        return !jdbc.queryForList("SELECT id FROM local_device_state_sync WHERE scope_key=? AND credential_generation=? AND last_attempt_at=? FOR UPDATE",
                key, generation, Timestamp.from(attempt)).isEmpty();
    }

    private Object[] values(String key, String deviceKey, String generation, AuthorizedDevice device,
            Object value, Instant observed, Instant committed) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) source.forEach((name, item) -> fields.put(String.valueOf(name), item));
        else if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Unsupported state shape");
        List<Object> row = new ArrayList<>();
        Collections.addAll(row, key, deviceKey, device.id(), device.kind(), value == null ? "UNKNOWN" : value instanceof Boolean ? "BOOLEAN" : "OBJECT",
                value instanceof Boolean flag ? flag : null);
        for (Field field : DECIMALS) row.add(decimal(fields.remove(field.wire())));
        for (Field field : TEXT) {
            Object fieldValue = fields.remove(field.wire());
            if (fieldValue != null && !(fieldValue instanceof String)) throw new IllegalArgumentException("Invalid state text field");
            row.add(fieldValue);
        }
        if (observed != null) fields.remove("readTime");
        try { row.add(mapper.writeValueAsString(fields)); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("Invalid state extensions", failure); }
        Collections.addAll(row, observed == null ? null : Timestamp.from(observed), Timestamp.from(committed), generation,
                Timestamp.from(committed), Timestamp.from(committed));
        return row.toArray();
    }

    private Stored stored(ResultSet rs) throws SQLException {
        Instant observed = instant(rs, "observed_at");
        Object value = null;
        String shape = rs.getString("value_shape");
        if ("BOOLEAN".equals(shape)) {
            boolean flag = rs.getBoolean("boolean_value");
            value = rs.wasNull() ? null : flag;
        } else if ("OBJECT".equals(shape)) {
            Map<String, Object> fields;
            try { fields = new LinkedHashMap<>(mapper.readValue(rs.getString("extensions_json"), EXTENSIONS)); }
            catch (java.io.IOException failure) { throw unavailable(); }
            for (Field field : DECIMALS) {
                BigDecimal number = rs.getBigDecimal(field.column());
                if (number != null) fields.put(field.wire(), number.stripTrailingZeros());
            }
            for (Field field : TEXT) {
                String text = rs.getString(field.column());
                if (text != null) fields.put(field.wire(), text);
            }
            if ("energy".equals(rs.getString("device_kind")) && observed != null) fields.put("readTime", observed.toString());
            value = fields.isEmpty() ? null : fields;
        } else if (!"UNKNOWN".equals(shape)) throw unavailable();
        return new Stored(value, observed, instant(rs, "synchronized_at"));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        BigDecimal decimal;
        try { decimal = value instanceof BigDecimal exact ? exact : new BigDecimal(value.toString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid state decimal", invalid); }
        BigDecimal stored = decimal.setScale(30, RoundingMode.UNNECESSARY);
        if (stored.precision() > 65) throw new IllegalArgumentException("State decimal exceeds DECIMAL(65,30)");
        return stored;
    }

    private static List<AuthorizedDevice> requested(AuthorizedDeviceScope scope, List<String> ids) {
        return ids.stream().map(id -> scope.device(id).orElseThrow(JdbcDeviceObservations::outsideScope)).toList();
    }
    private static List<Map<String, Object>> videoBlocks(List<AuthorizedDevice> devices, Map<String, Object> states) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuthorizedDevice device : devices) {
            if (!"video".equals(device.kind())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", device.id()); row.put("name", device.name());
            row.put("location", device.installAddress().isBlank() ? device.name() : device.installAddress());
            row.put("online", states.get(device.id())); rows.add(row);
        }
        return rows.isEmpty() ? List.of() : List.of(Map.of("type", "video", "devices", rows));
    }
    private static Instant parseTime(String time) {
        if (time == null || time.isBlank()) return null;
        try { return Instant.parse(time); }
        catch (java.time.DateTimeException invalid) { return null; }
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static BusinessException preparing() { return new BusinessException("DEVICE_STATE_PREPARING", "设备状态准备中，请稍后重试", 503); }
    private static BusinessException unavailable() { return new BusinessException("DEVICE_STATE_UNAVAILABLE", "本地设备状态暂不可用", 503); }
    private static BusinessException outsideScope() { return new BusinessException("DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403); }
    private record Stored(Object value, Instant observed, Instant synchronizedAt) { }
    private record Progress(String state, Instant success) { }
}
