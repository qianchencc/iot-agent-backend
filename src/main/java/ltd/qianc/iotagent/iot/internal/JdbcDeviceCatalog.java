package ltd.qianc.iotagent.iot.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCatalogSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared display catalog. Network collection and authorization remain outside the publication transaction. */
final class JdbcDeviceCatalog {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeviceCatalogSource source;
    private final String gateway;
    private final Clock clock;
    private final long intervalSeconds;

    JdbcDeviceCatalog(JdbcTemplate jdbc, TransactionTemplate transactions, DeviceCatalogSource source,
            String gateway, Clock clock, long intervalSeconds) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.source = source;
        this.gateway = gateway;
        this.clock = clock;
        this.intervalSeconds = intervalSeconds;
    }

    Map<String, Object> read(RequestIdentity identity) {
        String key = register(identity);
        return transactions.execute(tx -> {
            // Publication and reading lock the same row: metadata and records always describe one full batch.
            Progress progress = progress(key, true);
            if (progress.version() == 0 && "FAILED".equals(progress.state()))
                throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "设备目录同步失败，请稍后重试", 503);
            if (progress.version() == 0) throw new BusinessException("IOT_SCOPE_PREPARING", "设备目录准备中，请稍后重试", 503);
            List<Map<String, Object>> devices = jdbc.query("SELECT source_device_id,device_name,device_kind,install_address "
                            + "FROM local_device_catalog WHERE catalog_key=? ORDER BY device_kind,source_device_id",
                    (rs, row) -> Map.<String, Object>of("id", rs.getString(1), "name", rs.getString(2),
                            "kind", rs.getString(3), "location", rs.getString(4)), key);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", "company");
            result.put("devices", devices);
            result.put("synchronizedAt", progress.success().toString());
            result.put("cache", Map.of("status", "FAILED".equals(progress.state()) || !clock.instant().isBefore(progress.nextDue()) ? "stale" : "fresh",
                    "updatedAt", progress.success().toString(), "refreshing", "SYNCING".equals(progress.state())));
            return result;
        });
    }

    Map<String, Map<String, Object>> safetyMetadata(AuthorizedDeviceScope scope) {
        List<String> ids = new ArrayList<>();
        for (var device : scope.devices()) {
            if ("safety".equals(device.kind()) && device.hubDeviceId() != null && !device.hubDeviceId().isBlank())
                ids.add(device.hubDeviceId());
        }
        if (ids.isEmpty()) return Map.of();
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(key(scope.identity().tenantCode()));
        args.addAll(ids);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        jdbc.query("SELECT source_device_id,device_name,install_address FROM local_device_catalog "
                        + "WHERE catalog_key=? AND device_kind='safety' AND source_device_id IN ("
                        + String.join(",", java.util.Collections.nCopies(ids.size(), "?")) + ")",
                rs -> { result.put(rs.getString(1), Map.of("name", rs.getString(2), "location", rs.getString(3))); },
                args.toArray());
        return result;
    }

    void synchronize(RequestIdentity identity, Runnable revalidate) {
        String key = register(identity);
        Instant attempt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        Timestamp now = Timestamp.from(attempt);
        if (jdbc.update("UPDATE local_device_catalog_scopes SET state='SYNCING',last_attempt_at=?,next_due_at=?,update_time=? "
                        + "WHERE catalog_key=? AND next_due_at<=?", now, Timestamp.from(attempt.plusSeconds(intervalSeconds)), now, key, now) != 1) return;
        try {
            List<DeviceCatalogSource.Entry> devices = source.collect(identity);
            if (devices == null) throw new IllegalStateException("Missing complete device catalog");
            revalidate.run();
            transactions.executeWithoutResult(tx -> {
                Progress expected = progress(key, true);
                // Another due run may have started after a long source call; never overwrite its newer batch.
                if (!attempt.equals(expected.attempt())) return;
                Map<String, Map<String, DeviceCatalogSource.Entry>> previous = new LinkedHashMap<>();
                jdbc.query("SELECT source_device_id,device_name,device_kind,install_address FROM local_device_catalog WHERE catalog_key=?",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                            var device = new DeviceCatalogSource.Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
                            previous.computeIfAbsent(device.kind(), ignored -> new LinkedHashMap<>()).put(device.id(), device);
                        }, key);
                Timestamp success = Timestamp.from(clock.instant());
                List<Object[]> inserts = new ArrayList<>();
                List<Object[]> updates = new ArrayList<>();
                for (DeviceCatalogSource.Entry device : devices) {
                    Map<String, DeviceCatalogSource.Entry> kind = previous.get(device.kind());
                    DeviceCatalogSource.Entry old = kind == null ? null : kind.remove(device.id());
                    if (old == null) {
                        inserts.add(new Object[]{key, device.id(), device.kind(), device.name(), device.location(), success, success});
                    } else if (!device.equals(old)) {
                        updates.add(new Object[]{device.name(), device.location(), success, key, device.id(), device.kind()});
                    }
                }
                List<Object[]> deletes = new ArrayList<>();
                for (Map<String, DeviceCatalogSource.Entry> kind : previous.values()) {
                    for (DeviceCatalogSource.Entry removed : kind.values()) {
                        deletes.add(new Object[]{key, removed.id(), removed.kind()});
                    }
                }
                if (!deletes.isEmpty()) jdbc.batchUpdate("DELETE FROM local_device_catalog WHERE catalog_key=? AND source_device_id=? AND device_kind=?", deletes);
                if (!updates.isEmpty()) jdbc.batchUpdate("UPDATE local_device_catalog SET device_name=?,install_address=?,update_time=? "
                        + "WHERE catalog_key=? AND source_device_id=? AND device_kind=?", updates);
                if (!inserts.isEmpty()) jdbc.batchUpdate("INSERT INTO local_device_catalog(catalog_key,source_device_id,device_kind,"
                        + "device_name,install_address,create_time,update_time) VALUES (?,?,?,?,?,?,?)", inserts);
                jdbc.update("UPDATE local_device_catalog_scopes SET batch_version=batch_version+1,state='READY',last_success_at=?,"
                                + "next_due_at=?,update_time=? WHERE catalog_key=?", success,
                        Timestamp.from(success.toInstant().plusSeconds(intervalSeconds)), success, key);
            });
        } catch (RuntimeException failure) {
            jdbc.update("UPDATE local_device_catalog_scopes SET state='FAILED',update_time=? WHERE catalog_key=? AND last_attempt_at=?",
                    Timestamp.from(clock.instant()), key, now);
        }
    }

    private String register(RequestIdentity identity) {
        String key = key(identity.tenantCode());
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("INSERT IGNORE INTO local_device_catalog_scopes(catalog_key,gateway_instance,tenant_code,state,next_due_at,create_time,update_time) "
                + "VALUES (?,?,?,'PREPARING',?,?,?)", key, gateway, identity.tenantCode(), now, now, now);
        return key;
    }

    private Progress progress(String key, boolean lock) {
        return jdbc.queryForObject("SELECT batch_version,state,last_attempt_at,last_success_at,next_due_at "
                        + "FROM local_device_catalog_scopes WHERE catalog_key=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Progress(rs.getLong(1), rs.getString(2), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5))), key);
    }

    private String key(String tenant) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((gateway + "\u0000" + tenant + "\u0000DEVICE_CATALOG").getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private record Progress(long version, String state, Instant attempt, Instant success, Instant nextDue) { }
}
