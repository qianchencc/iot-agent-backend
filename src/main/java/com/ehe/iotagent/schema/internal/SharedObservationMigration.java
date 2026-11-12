package com.ehe.iotagent.schema.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Frozen v2 key encoding. No credentials, source requests, invented samples or binding guesses. */
final class SharedObservationMigration {
    static final String KEY = "local-shared-observations-v2";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    SharedObservationMigration(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    void migrate() {
        transactions.executeWithoutResult(tx -> {
            if (jdbc.update("INSERT IGNORE INTO storage_migrations(migration_key) VALUES (?)", KEY) == 0) return;
            Map<String, Map<String, Object>> scopes = index(rows("SELECT scope_key,gateway_instance,tenant_code,source,last_success_at FROM local_sync_scopes"), "scope_key");
            Map<String, Map<String, Object>> devices = new LinkedHashMap<>();
            Map<String, List<String>> sensors = new LinkedHashMap<>();
            for (Map<String, Object> device : rows("SELECT * FROM local_company_devices")) {
                String owner = text(device, "scope_key");
                devices.put(owner + ":" + text(device, "device_key"), device);
                if ("environment".equals(device.get("device_kind"))) {
                    sensors.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(deviceKey(device));
                }
            }
            migrateCurrent(scopes, devices);
            Map<String, String> histories = new LinkedHashMap<>();
            sensors.forEach((owner, members) -> {
                Map<String, Object> scope = scopes.get(owner);
                if (scope != null) {
                    members.sort(String::compareTo);
                    histories.put(owner, hash("ENVIRONMENT_FACTS_V2\0" + realm(scope) + "\0" + String.join("\0", members)));
                }
            });
            migrateHistory(histories);
        });
    }

    private void migrateCurrent(Map<String, Map<String, Object>> scopes, Map<String, Map<String, Object>> devices) {
        Map<String, Map<String, Object>> priorProgress = index(rows("SELECT * FROM local_device_state_sync"), "scope_key");
        Map<String, Map<String, Object>> winners = new LinkedHashMap<>();
        Map<String, Map<String, Object>> checkpoints = new LinkedHashMap<>();
        List<Long> retired = new ArrayList<>();
        for (Map<String, Object> row : rows("SELECT * FROM local_device_states ORDER BY synchronized_at,id")) {
            String owner = text(row, "scope_key");
            Map<String, Object> scope = scopes.get(owner);
            if (scope == null) continue;
            Map<String, Object> device = devices.get(owner + ":" + text(row, "device_key"));
            // A later directory publication may have changed a control binding without a newer sample.
            if (device == null || scope.get("last_success_at") == null
                    || compare(row.get("synchronized_at"), scope.get("last_success_at")) < 0) continue;
            Map<String, Object> fact = new LinkedHashMap<>(row);
            fact.put("scope_key", realm(scope));
            fact.put("device_key", deviceKey(device));
            String identity = text(fact, "scope_key") + ":" + text(fact, "device_key");
            Map<String, Object> old = winners.get(identity);
            if (old == null || newerState(fact, old)) {
                winners.put(identity, fact);
                Map<String, Object> progress = priorProgress.get(owner);
                if (progress != null) {
                    Map<String, Object> shared = new LinkedHashMap<>(progress);
                    shared.put("scope_key", hash(text(fact, "scope_key") + "\0" + text(fact, "device_key")));
                    checkpoints.put(identity, shared);
                }
            }
            retired.add(((Number) row.get("id")).longValue());
        }
        upsert("local_device_states", winners.values());
        upsert("local_device_state_sync", checkpoints.values());
        deleteIds("local_device_states", retired);
        jdbc.update("DELETE FROM local_device_state_sync WHERE scope_key IN (SELECT scope_key FROM local_sync_scopes)");
    }

    private void migrateHistory(Map<String, String> histories) {
        Map<String, Map<String, Object>> samples = new LinkedHashMap<>();
        List<Long> retiredSamples = new ArrayList<>();
        for (Map<String, Object> row : rows("SELECT * FROM local_environment_samples ORDER BY synchronized_at,id")) {
            String shared = histories.get(text(row, "scope_key"));
            if (shared == null) continue;
            Map<String, Object> fact = new LinkedHashMap<>(row);
            fact.put("scope_key", shared);
            samples.put(shared + ":" + text(row, "sample_key"), fact);
            retiredSamples.add(((Number) row.get("id")).longValue());
        }
        upsert("local_environment_samples", samples.values());
        deleteIds("local_environment_samples", retiredSamples);
        Map<String, Map<String, Object>> progress = new LinkedHashMap<>();
        List<Long> retiredProgress = new ArrayList<>();
        for (Map<String, Object> row : rows("SELECT * FROM local_environment_sync ORDER BY update_time,id")) {
            String shared = histories.get(text(row, "scope_key"));
            if (shared == null) continue;
            Map<String, Object> previous = progress.get(shared);
            Map<String, Object> merged = new LinkedHashMap<>(row);
            merged.put("scope_key", shared);
            if (previous != null) {
                for (String field : List.of("current_success_at", "full_success_at", "full_covered_until")) {
                    if (compare(previous.get(field), merged.get(field)) > 0) merged.put(field, previous.get(field));
                }
                if (row.get("full_success_at") == null && row.get("full_failure_code") == null && previous.get("full_success_at") != null) {
                    for (String field : List.of("full_state", "full_next_due_at", "full_failure_code")) merged.put(field, previous.get(field));
                }
                merged.put("publication_version", Math.max(((Number) previous.get("publication_version")).longValue(),
                        ((Number) row.get("publication_version")).longValue()));
            }
            progress.put(shared, merged);
            retiredProgress.add(((Number) row.get("id")).longValue());
        }
        upsert("local_environment_sync", progress.values());
        deleteIds("local_environment_sync", retiredProgress);
        Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        List<Long> retiredRequests = new ArrayList<>();
        for (Map<String, Object> row : rows("SELECT * FROM local_environment_requests ORDER BY update_time,id")) {
            String shared = histories.get(text(row, "scope_key"));
            if (shared == null) continue;
            Map<String, Object> demand = new LinkedHashMap<>(row);
            demand.put("scope_key", shared);
            requests.put(shared + ":" + text(row, "credential_generation") + ":" + text(row, "request_key"), demand);
            retiredRequests.add(((Number) row.get("id")).longValue());
        }
        upsert("local_environment_requests", requests.values());
        deleteIds("local_environment_requests", retiredRequests);
    }

    private boolean newerState(Map<String, Object> next, Map<String, Object> old) {
        int sampling = compare(next.get("observed_at"), old.get("observed_at"));
        return sampling > 0 || sampling == 0 && compare(next.get("synchronized_at"), old.get("synchronized_at")) >= 0;
    }

    private List<Map<String, Object>> rows(String sql) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var metadata = rs.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                String name = metadata.getColumnLabel(column).toLowerCase(Locale.ROOT);
                int type = metadata.getColumnType(column);
                Object value = name.endsWith("_json") ? rs.getString(column)
                        : type == java.sql.Types.TIMESTAMP || type == java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                        ? rs.getTimestamp(column) : rs.getObject(column);
                row.put(name, value);
            }
            return row;
        });
    }

    private void upsert(String table, java.util.Collection<Map<String, Object>> rows) {
        if (rows.isEmpty()) return;
        List<String> fields = rows.iterator().next().keySet().stream().filter(field -> !"id".equals(field)).toList();
        String updates = fields.stream().filter(field -> !List.of("scope_key", "device_key", "sample_key", "create_time").contains(field))
                .map(field -> field + "=VALUES(" + field + ")").collect(java.util.stream.Collectors.joining(","));
        String sql = "INSERT INTO " + table + "(" + String.join(",", fields) + ") VALUES ("
                + String.join(",", java.util.Collections.nCopies(fields.size(), "?")) + ") ON DUPLICATE KEY UPDATE " + updates;
        jdbc.batchUpdate(sql, rows.stream().map(row -> fields.stream().map(row::get).toArray()).toList());
    }

    private void deleteIds(String table, List<Long> ids) {
        if (!ids.isEmpty()) jdbc.batchUpdate("DELETE FROM " + table + " WHERE id=?", ids.stream().map(id -> new Object[]{id}).toList());
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows, String field) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var row : rows) result.put(text(row, field), row);
        return result;
    }

    private String deviceKey(Map<String, Object> row) {
        JsonNode metadata;
        try { metadata = mapper.readTree(text(row, "metadata_json")); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("Invalid persisted observation binding", invalid); }
        return hash(text(row, "device_kind") + "\0" + text(row, "source_device_id") + "\0" + text(row, "control_ref") + "\0"
                + text(row, "office_device_ref") + "\0" + text(row, "hub_device_id") + "\0" + text(row, "smart_area_code") + "\0"
                + metadata.path("code").asText("") + "\0" + metadata.path("companyAcDetailActionId").asText(""));
    }

    private static String realm(Map<String, Object> scope) {
        return hash("DEVICE_FACTS_V2\0" + text(scope, "gateway_instance") + "\0" + text(scope, "tenant_code") + "\0" + text(scope, "source"));
    }
    private static String text(Map<String, Object> row, String field) { return row.get(field) == null ? "" : row.get(field).toString(); }
    private static int compare(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return ((Timestamp) left).compareTo((Timestamp) right);
    }
    private static String hash(String material) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
