package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.schema.SchemaMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class SafetyHistoryBehaviorTest {
    @Test void boundsBelongToTheAuthorizedTypeNotTheSelectedWindowOrFirstPage() {
        try (var f = new Fixture()) {
            for (int i = 0; i < 105; i++) f.rows.add(row("gas", "gas", "" + i,
                    Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i).toString()));
            f.rows.add(row("smoke", "smoke", "smoke-first", "2010-01-01T00:00:00Z"));
            f.rows.add(row("gas", "outside", "not-authorized", "1990-01-01T00:00:00Z"));
            f.publish();
            int calls = f.calls.get();
            f.offline = true;
            var response = f.read("gas", "2021-01-01T00:00:00Z", "2021-02-01T00:00:00Z");
            assertTrue(((List<?>) response.payload().get("items")).isEmpty());
            assertEquals("2020-01-01T00:00:00Z", ((Map<?, ?>) response.payload().get("availableWindow")).get("from"));
            assertEquals("2010-01-01T00:00:00Z", ((Map<?, ?>) f.read("smoke", null, null).payload().get("availableWindow")).get("from"));
            assertNull(((Map<?, ?>) f.read("water", null, null).payload().get("availableWindow")).get("from"));
            assertEquals(calls, f.calls.get(), "ordinary reads must never collect from the source");
        }
    }

    @Test void eventRevisionsPreserveIdentityAndApplyCaseOnlyNamesAndNullableFlags() {
        try (var f = new Fixture()) {
            var original = new LinkedHashMap<>(row("gas", "gas", "fixture-revised", "2020-01-01T00:00:00Z"));
            original.put("deviceName", "Fixture DEVICE");
            f.rows.add(original);
            f.rows.add(row("gas", "gas", "fixture-other", "2020-01-01T00:00:00Z"));
            f.publish();
            var revised = new LinkedHashMap<>(original);
            revised.put("deviceName", "Fixture device");
            f.rows.set(0, revised);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            var detail = new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENT,
                    Map.of("ledger", "gas", "deviceId", "gas", "id", "fixture-revised"));
            assertEquals("Fixture device", ((Map<?, ?>) f.directory.safetyHistory().read(detail).block()
                    .payload().get("record")).get("recordedDeviceName"));
            revised.put("alarm", null);
            revised.put("report", true);
            f.rows.set(0, revised);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            Map<?, ?> record = (Map<?, ?>) f.directory.safetyHistory().read(detail).block().payload().get("record");
            assertEquals("Fixture device", record.get("recordedDeviceName"));
            assertNull(record.get("alarm"));
            assertEquals(true, record.get("report"));
            assertEquals("2020-01-01T00:00:00Z", record.get("occurredAt"));
            revised.put("alarm", false);
            revised.put("report", null);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            int calls = f.calls.get();
            f.offline = true;
            record = (Map<?, ?>) f.directory.safetyHistory().read(detail).block().payload().get("record");
            assertEquals(false, record.get("alarm"));
            assertNull(record.get("report"));
            assertEquals(2, ((List<?>) f.read("gas", null, null).payload().get("items")).size());
            assertEquals(calls, f.calls.get());
        }
    }

    @Test void anotherPrincipalReusesFactsOnlyAfterItsOwnGrantIsPublished() {
        try (var f = new Fixture()) {
            f.rows.add(row("gas", "gas", "original", "2020-01-01T00:00:00Z"));
            f.publish();
            int collected = f.calls.get();
            var member = f.identity("Bearer fixture-member", "fixture-tenant");
            f.register(member);
            assertThrows(BusinessException.class, () -> f.read(member, "gas", null, null, null));
            f.directory.synchronizeDue();
            f.directory.synchronizeHistoryDue();
            assertEquals("2020-01-01T00:00:00Z", ((Map<?, ?>) f.read(member, "gas", null, null, null).payload().get("availableWindow")).get("from"));
            assertEquals(collected, f.calls.get(), "same grants must not cause another full historical pull");
            var otherTenant = f.identity("Bearer fixture-other-tenant", "different-tenant");
            f.register(otherTenant);
            f.directory.synchronizeDue();
            assertEquals("SAFETY_HISTORY_PREPARING", assertThrows(BusinessException.class,
                    () -> f.read(otherTenant, "gas", null, null, null)).code());
        }
    }

    @Test void failedFullBatchCannotPublishAnInventedBoundaryAndCanRecover() {
        try (var f = new Fixture()) {
            for (int i = 0; i < 105; i++) f.rows.add(row("gas", "gas", "" + i,
                    Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i).toString()));
            f.failPage = 1;
            f.publish();
            assertEquals("SAFETY_HISTORY_PREPARING", assertThrows(BusinessException.class,
                    () -> f.read("gas", null, null)).code());
            f.failPage = -1;
            f.clock.advance(61);
            f.directory.synchronizeHistoryDue();
            assertEquals("2020-01-01T00:00:00Z", ((Map<?, ?>) f.read("gas", null, null).payload().get("availableWindow")).get("from"));
        }
    }

    @Test void localPaginationLatestAndOldDetailRemainAvailableAfterRestartWithSourceOffline() {
        try (var f = new Fixture()) {
            for (int i = 0; i < 105; i++) f.rows.add(row("gas", "gas", "" + i,
                    Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i).toString()));
            f.publish();
            f.directory.close();
            f.open();
            f.offline = true;
            int calls = f.calls.get();
            var first = f.read("gas", null, null).payload();
            var second = f.read(f.identity(), "gas", null, null, (String) first.get("nextCursor")).payload();
            assertEquals(20, ((List<?>) first.get("items")).size());
            assertEquals("84", ((Map<?, ?>) ((List<?>) second.get("items")).getFirst()).get("eventId"));
            var other = f.identity("Bearer unregistered", "fixture-tenant");
            assertThrows(BusinessException.class, () -> f.read(other, "gas", null, null, (String) first.get("nextCursor")));
            var detail = f.directory.safetyHistory().read(new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENT,
                    Map.of("ledger", "gas", "deviceId", "gas", "id", "0"))).block().payload();
            assertEquals("0", ((Map<?, ?>) detail.get("record")).get("eventId"));
            var latest = f.directory.safetyHistory().read(new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENTS,
                    Map.of("type", "gas", "selection", "latest", "to", "2020-01-01T00:00:50Z"))).block().payload();
            assertEquals("50", ((Map<?, ?>) latest.get("record")).get("eventId"));
            assertEquals(calls, f.calls.get());
        }
    }

    @Test void currentMetadataUsesFireBusinessSourceWithoutMixingHardwareCatalog() {
        try (var f = new Fixture()) {
            f.grants = List.of(new AuthorizedDevice("gas", "safety", "旧业务燃气设备", "旧业务位置", true,
                    null, "gas", "fixture-hardware", null, List.of(), List.of(), Map.of()));
            f.catalog = List.of(new DeviceCatalogSource.Entry("fixture-hardware", "当前燃气设备", "safety", "当前安装位置"));
            f.rows.add(row("gas", "gas", "original-event", "2020-01-01T00:00:00Z"));
            f.publish();
            int calls = f.calls.get();
            f.offline = true;
            Map<?, ?> listed = (Map<?, ?>) ((List<?>) f.read("gas", null, null).payload().get("items")).getFirst();
            assertEquals("测试记录设备", listed.get("deviceName"));
            assertEquals("旧业务燃气设备", ((Map<?, ?>) listed.get("currentDevice")).get("name"));
            assertEquals("旧业务位置", ((Map<?, ?>) listed.get("currentDevice")).get("location"));
            assertEquals("测试记录设备", listed.get("recordedDeviceName"));
            assertEquals("original-event", listed.get("eventId"));
            assertEquals("2020-01-01T00:00:00Z", listed.get("occurredAt"));
            assertEquals(calls, f.calls.get(), "reading current display information must not collect alarm history");
            f.offline = false;
            f.catalog = List.of(new DeviceCatalogSource.Entry("fixture-hardware", "再次更名燃气设备", "safety", "新安装位置"));
            f.grants = List.of(new AuthorizedDevice("gas", "safety", "更名后的消防燃气设备", "消防更新位置", true,
                    null, "gas", "fixture-hardware", null, List.of(), List.of(), Map.of()));
            f.clock.advance(61);
            f.directory.synchronizeDue();
            calls = f.calls.get();
            f.offline = true;
            var detail = f.directory.safetyHistory().read(new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENT,
                    Map.of("ledger", "gas", "deviceId", "gas", "id", "original-event"))).block().payload();
            var latest = f.directory.safetyHistory().read(new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENTS,
                    Map.of("type", "gas", "selection", "latest", "to", f.clock.instant().toString()))).block().payload();
            for (var payload : List.of(detail, latest)) {
                Map<?, ?> record = (Map<?, ?>) payload.get("record");
                assertEquals("测试记录设备", record.get("deviceName"));
                assertEquals("更名后的消防燃气设备", ((Map<?, ?>) record.get("currentDevice")).get("name"));
                assertEquals("消防更新位置", record.get("location"));
                assertEquals("测试记录设备", record.get("recordedDeviceName"));
                assertEquals("original-event", record.get("eventId"));
                assertEquals("2020-01-01T00:00:00Z", record.get("occurredAt"));
            }
            assertEquals(calls, f.calls.get(), "displaying a renamed old event must not collect alarm history");
        }
    }

    @Test void eventCamerasFollowTheOriginalRecordWhileCurrentDeviceMetadataChanges() {
        try (var f = new Fixture()) {
            f.grants = List.of(
                    new AuthorizedDevice("gas", "safety", "10楼旧燃气设备", "10楼旧位置", true,
                            null, "gas", "fixture-hardware", null, List.of(), List.of(), Map.of()),
                    new AuthorizedDevice("camera-old", "video", "10楼监控", "10楼旧位置", true,
                            null, null, "camera-hardware-old", null, List.of(), List.of(), Map.of()),
                    new AuthorizedDevice("camera-current", "video", "20楼监控", "20楼当前位置", true,
                            null, null, "camera-hardware-current", null, List.of(), List.of(), Map.of()));
            f.catalog = List.of(new DeviceCatalogSource.Entry("fixture-hardware", "20楼当前燃气设备", "safety", "20楼当前位置"));
            var event = new LinkedHashMap<>(row("gas", "gas", "old-event", "2020-01-01T00:00:00Z"));
            event.put("deviceName", "10楼旧位置燃气感应器");
            f.rows.add(event);
            f.publish();
            f.offline = true;
            IotAdapter local = new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity id) {
                    return Mono.fromCallable(() -> f.directory.loadScope(id));
                }
            };
            var adapter = ObservationAdapterFactory.composed(
                    request -> Mono.error(new AssertionError("ordinary read contacted source")), local,
                    identity -> Mono.just(Map.of()), null, null, f.directory.safetyHistory()::read, null, f.clock);
            var payload = adapter.read(new ObservationRequest(f.identity(), ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                    Map.of("ledger", "gas", "deviceId", "gas", "id", "old-event"))).block().payload();
            var candidates = (List<?>) payload.get("candidates");
            assertEquals(1, candidates.size());
            Map<?, ?> candidate = (Map<?, ?>) candidates.getFirst();
            assertEquals("camera-old", candidate.get("deviceId"));
            assertEquals("2020-01-01T07:45:00.000+08:00", candidate.get("beginTime"));
            assertEquals("2020-01-01T08:15:00.000+08:00", candidate.get("endTime"));
            Map<?, ?> record = (Map<?, ?>) payload.get("record");
            assertEquals("10楼旧位置燃气感应器", record.get("deviceName"));
            assertEquals("10楼旧燃气设备", ((Map<?, ?>) record.get("currentDevice")).get("name"));
            assertEquals("2020-01-01T00:00:00Z", record.get("occurredAt"));
            assertEquals("old-event", record.get("eventId"));
        }
    }

    @Test void currentDisplayMetadataRemainsTenantScopedAndDoesNotGrantCatalogOnlyHardware() {
        try (var f = new Fixture()) {
            f.grants = List.of(new AuthorizedDevice("gas", "safety", "甲租户燃气设备", "甲消防位置", true,
                    null, "gas", "fixture-hardware", null, List.of(), List.of(), Map.of()));
            f.catalog = List.of(
                    new DeviceCatalogSource.Entry("fixture-hardware", "甲租户燃气设备", "safety", "甲位置"),
                    new DeviceCatalogSource.Entry("catalog-only", "未授权燃气设备", "safety", "未授权位置"));
            f.rows.add(row("gas", "gas", "allowed-event", "2020-01-01T00:00:00Z"));
            f.rows.add(row("gas", "catalog-only", "not-authorized", "2019-01-01T00:00:00Z"));
            f.publish();
            var secondTenant = f.identity("Bearer fixture-second-tenant", "fixture-second-tenant");
            f.catalog = List.of(new DeviceCatalogSource.Entry("fixture-hardware", "乙租户燃气设备", "safety", "乙位置"));
            f.grants = List.of(new AuthorizedDevice("gas", "safety", "乙租户燃气设备", "乙消防位置", true,
                    null, "gas", "fixture-hardware", null, List.of(), List.of(), Map.of()));
            f.register(secondTenant);
            f.directory.synchronizeDue();
            f.directory.synchronizeHistoryDue();
            f.offline = true;
            for (var identity : List.of(f.identity(), secondTenant)) {
                var items = (List<?>) f.read(identity, "gas", null, null, null).payload().get("items");
                assertEquals(1, items.size());
                Map<?, ?> record = (Map<?, ?>) items.getFirst();
                assertEquals("allowed-event", record.get("eventId"));
                assertEquals(identity.equals(secondTenant) ? "乙租户燃气设备" : "甲租户燃气设备",
                        ((Map<?, ?>) record.get("currentDevice")).get("name"));
            }
        }
    }

    static Map<String, Object> row(String ledger, String device, String id, String at) {
        return Map.of("ledger", ledger, "deviceId", device, "eventId", id,
                "deviceName", "测试记录设备", "occurredAt", at, "alarm", true, "report", false);
    }

    static final class Fixture implements AutoCloseable {
        final javax.sql.DataSource database;
        final EnergyHistoryFixture.MutableClock clock = new EnergyHistoryFixture.MutableClock();
        final List<Map<String, Object>> rows = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final ObjectMapper mapper = new ObjectMapper();
        final MemoryBarrier barrier = new MemoryBarrier();
        final ReadSnapshots snapshots;
        volatile boolean offline;
        volatile int failPage = -1;
        volatile List<AuthorizedDevice> grants = List.of(
                new AuthorizedDevice("gas", "safety", "测试燃气设备", "测试位置", true),
                new AuthorizedDevice("smoke", "safety", "测试烟感设备", "测试位置", true),
                new AuthorizedDevice("water", "safety", "测试水浸设备", "测试位置", true));
        volatile List<DeviceCatalogSource.Entry> catalog = List.of();
        final IotAdapter source = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity id) {
                if (offline) return Mono.error(new AssertionError("ordinary read contacted directory source"));
                return Mono.just(AuthorizedDeviceScope.of(id, id.source(), grants));
            }
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity id) {
                return Mono.just(AuthorizedDeviceScope.of(id, id.source(), List.of()));
            }
            @Override public Mono<DeviceStateReadResult> collectCurrentStates(RequestIdentity id, AuthorizedDeviceScope scope) {
                return Mono.just(new DeviceStateReadResult(Map.of(), null));
            }
        };
        final ObservationAdapter historySource = request -> {
            calls.incrementAndGet();
            if (offline) return Mono.error(new AssertionError("ordinary read contacted alarm source"));
            int page = Integer.parseInt(request.parameters().getOrDefault("page", "0"));
            if (page == failPage) return Mono.error(new IllegalStateException("synthetic page failure"));
            String ledger = request.parameters().get("ledger");
            var matching = rows.stream().filter(row -> ledger.equals(row.get("ledger")))
                    .sorted((a, b) -> b.get("occurredAt").toString().compareTo(a.get("occurredAt").toString())).toList();
            int from = Math.min(page * 100, matching.size()), to = Math.min(from + 100, matching.size());
            return Mono.just(ObservationResponse.available(Map.of("items", matching.subList(from, to),
                    "last", to == matching.size(), "total", matching.size(), "scanned", to - from)));
        };
        DeviceDirectory directory;
        Fixture() { this(database()); }
        Fixture(javax.sql.DataSource database) {
            this.database = database;
            jdbc = new JdbcTemplate(database);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
            new SchemaMigrations(database).ensureLocalCompanyDirectory();
            if (database instanceof JdbcDataSource)
                jdbc.execute("ALTER TABLE local_company_devices ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
            snapshots = ReadSnapshots.create(clock, Runnable::run, Runnable::run, null, barrier, mapper, ReadSnapshots.Settings.defaults());
            open();
        }
        private static JdbcDataSource database() {
            var database = new JdbcDataSource();
            database.setURL("jdbc:h2:mem:safety_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            return database;
        }
        void open() {
            directory = DeviceDirectory.jdbc(jdbc, transactions, mapper, source, historySource,
                    presented -> principal(presented.authorization(), presented.tenantCode()), snapshots, barrier,
                    "https://safety-fixture.example.invalid", "cd".repeat(32), clock, request -> {
                        if (offline) throw new AssertionError("ordinary read contacted hardware catalog source");
                        return catalog;
                    });
        }
        static PrincipalInfo principal(String token, String tenant) { return new PrincipalInfo(token, tenant, token, "fixture-realm"); }
        RequestIdentity identity() { return identity("Bearer fixture-safety", "fixture-tenant"); }
        RequestIdentity identity(String token, String tenant) { return new RequestIdentity(token, tenant, IotSourceEnum.COMPANY, principal(token, tenant).ownerKey()); }
        void register(RequestIdentity identity) {
            try { directory.loadBatch(identity); }
            catch (BusinessException failure) { if (!"IOT_SCOPE_PREPARING".equals(failure.code())) throw failure; }
        }
        void publish() { register(identity()); directory.synchronizeDue(); directory.synchronizeHistoryDue(); }
        ObservationResponse read(String type, String from, String to) { return read(identity(), type, from, to, null); }
        ObservationResponse read(RequestIdentity id, String type, String from, String to, String cursor) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("type", type);
            if (from != null) query.put("from", from);
            if (to != null) query.put("to", to);
            if (cursor != null) query.put("cursor", cursor);
            return directory.safetyHistory().read(new ObservationRequest(id, ObservationOperationEnum.SAFETY_EVENTS, query)).block(Duration.ofSeconds(2));
        }
        @Override public void close() { directory.close(); snapshots.close(); }
    }
    static final class MemoryBarrier implements ReadSnapshots.Barrier {
        final Map<Key, State> states = new LinkedHashMap<>();
        @Override public Checkpoint startInstance(String instance) { return new Checkpoint(true, "fixture-epoch"); }
        @Override public boolean markClean(String instance) { return true; }
        @Override public Optional<State> read(Key key) { return Optional.ofNullable(states.get(key)); }
        @Override public boolean write(Key key, State state) { states.put(key, state); return true; }
        @Override public boolean writeIfCurrent(Key key, String generation, State state) {
            var current = states.get(key);
            if (generation == null ? current != null : current == null || !generation.equals(current.generation())) return false;
            return write(key, state);
        }
    }
}
