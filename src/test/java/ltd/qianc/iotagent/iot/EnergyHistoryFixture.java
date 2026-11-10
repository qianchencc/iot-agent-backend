package ltd.qianc.iotagent.iot;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.schema.SchemaMigrations;
import reactor.core.publisher.Mono;

/** Real JDBC persistence and public source protocol; all identities and records are synthetic. */
public final class EnergyHistoryFixture implements AutoCloseable {
    public static final String GATEWAY = "https://energy-fixture.example.invalid";
    public static final AuthorizedDevice METER = new AuthorizedDevice("fixture-meter-a", "energy", "测试电表甲", "测试位置甲", true);
    public final MutableClock clock = new MutableClock();
    public final ObjectMapper mapper = new ObjectMapper();
    public final JdbcTemplate jdbc;
    public final TransactionTemplate transactions;
    public final AtomicInteger sourceCalls = new AtomicInteger();
    public final AtomicInteger directoryCalls = new AtomicInteger();
    public volatile List<AuthorizedDevice> meters = List.of(METER);
    public String gatewayBase = GATEWAY;
    public volatile List<AuthorizedDevice> catalog;
    public volatile List<Map<String,Object>> rows = List.of();
    public volatile DeviceStateReadResult currentStates = new DeviceStateReadResult(Map.of(), null);
    public volatile boolean unavailable;
    public volatile boolean grantUnavailable;
    public volatile int failPage = -1;
    public volatile GatewayAuthorizationException denied;
    public volatile java.util.function.IntFunction<String> pageOverride;
    public Runnable beforeSampleRead;
    public volatile Runnable beforeStateRead;
    public Runnable beforeMeterRead;
    public Runnable afterSampleRead;
    private final MemoryBarrier barrier = new MemoryBarrier();
    private ReadSnapshots snapshots;
    public DeviceDirectory directory;
    public final IotAdapter gateway = new IotAdapter() {
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            directoryCalls.incrementAndGet();
            return Mono.just(AuthorizedDeviceScope.of(identity,identity.source(),catalog == null ? meters : catalog));
        }
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
            if (denied != null) return Mono.error(denied);
            if (grantUnavailable) return Mono.error(new IllegalStateException("synthetic dedicated scope offline"));
            return Mono.just(AuthorizedDeviceScope.of(identity,identity.source(),meters));
        }
        @Override public Mono<DeviceStateReadResult> collectCurrentStates(RequestIdentity identity,AuthorizedDeviceScope scope) {
            Runnable hook = beforeStateRead;
            if (hook != null) hook.run();
            return Mono.just(currentStates);
        }
    };
    public final ObservationAdapter source = ObservationAdapterFactory.webClient(
            org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
                sourceCalls.incrementAndGet();
                if (unavailable) return Mono.error(new IllegalStateException("synthetic data source offline"));
                var query = org.springframework.web.util.UriComponentsBuilder.fromUri(request.url()).build().getQueryParams();
                if (query.containsKey("startTime") || query.containsKey("endTime") || query.containsKey("equipmentId"))
                    throw new AssertionError("collector must read the unfiltered stream");
                int page = Integer.parseInt(query.getFirst("page"));
                if (page == failPage) return Mono.error(new IllegalStateException("synthetic page failed"));
                try {
                    String body;
                    if (pageOverride != null) body = pageOverride.apply(page);
                    else {
                        List<Map<String,Object>> ordered = rows.stream().sorted(Comparator.comparing(
                                row -> String.valueOf(row.get("readTime")),Comparator.reverseOrder())).toList();
                        int first = Math.min(page*500,ordered.size()),last = Math.min(first+500,ordered.size());
                        body = mapper.writeValueAsString(Map.of("code",200,"content",Map.of("content",ordered.subList(first,last),
                                "number",page,"last",last==ordered.size(),"totalElements",ordered.size(),"totalPages",(ordered.size()+499)/500)));
                    }
                    return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                            .header("Content-Type","application/json").body(body).build());
                } catch(java.io.IOException failure) { return Mono.error(failure); }
            }).build(),new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENERGY_HISTORY,"/energy-history")),
            java.time.Duration.ofSeconds(1),IotCallPolicy.passThrough());

    public EnergyHistoryFixture() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:energy_history_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(database) {
            @Override public <T> T query(org.springframework.jdbc.core.PreparedStatementCreator statement,
                    org.springframework.jdbc.core.PreparedStatementSetter parameters,
                    org.springframework.jdbc.core.ResultSetExtractor<T> extractor) {
                if (beforeMeterRead != null && statement instanceof org.springframework.jdbc.core.SqlProvider sql
                        && sql.getSql().contains("local_energy_meters")) {
                    Runnable publication = beforeMeterRead;
                    beforeMeterRead = null;
                    publication.run();
                }
                if (beforeSampleRead != null && statement instanceof org.springframework.jdbc.core.SqlProvider sql
                        && sql.getSql().contains("local_energy_samples")) {
                    Runnable publication = beforeSampleRead;
                    beforeSampleRead = null;
                    publication.run();
                }
                T result = super.query(statement, parameters, extractor);
                if (afterSampleRead != null && statement instanceof org.springframework.jdbc.core.SqlProvider sql
                        && sql.getSql().contains("local_energy_samples")) {
                    Runnable publication = afterSampleRead;
                    afterSampleRead = null;
                    publication.run();
                }
                return result;
            }
        };
        transactions = new TransactionTemplate(new DataSourceTransactionManager(database));
        new SchemaMigrations(database).ensureLocalCompanyDirectory();
        // Bound JSON strings are parsed by MySQL; H2's JSON column instead quotes them.
        jdbc.execute("ALTER TABLE local_company_devices ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
        jdbc.execute("ALTER TABLE local_device_states ALTER COLUMN extensions_json VARCHAR(1000000) NOT NULL");
        jdbc.execute("ALTER TABLE local_energy_meters ALTER COLUMN location_paths_json VARCHAR(1000000) NOT NULL");
        jdbc.execute("ALTER TABLE local_energy_meters ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
        open();
    }
    private void open() {
        snapshots = ReadSnapshots.create(clock,Runnable::run,Runnable::run,null,barrier,mapper,ReadSnapshots.Settings.defaults());
        directory = DeviceDirectory.jdbc(jdbc,transactions,mapper,gateway,source,
                presented -> principal(presented.authorization(),presented.tenantCode()),snapshots,barrier,gatewayBase,"ef".repeat(32),clock, request -> List.of());
    }
    public RequestIdentity identity() { return identity("Bearer fixture-energy","fixture-tenant"); }
    public RequestIdentity identity(String token,String tenant) {
        return new RequestIdentity(token,tenant,IotSourceEnum.COMPANY,principal(token,tenant).ownerKey());
    }
    private static PrincipalInfo principal(String token,String tenant) {
        return new PrincipalInfo(token,tenant,token.endsWith("other")?"fixture-other-principal":"fixture-principal","fixture-realm");
    }
    public EnergyHistory history() { return directory.energyHistory(); }
    public ReadSnapshots snapshots() { return snapshots; }
    public void register(RequestIdentity identity) {
        try { directory.loadBatch(identity); }
        catch(BusinessException pending) { if(!"IOT_SCOPE_PREPARING".equals(pending.code()))throw pending; }
    }
    public void synchronize() { directory.synchronizeDue(); directory.synchronizeHistoryDue(); }
    public void publish() { register(identity()); synchronize(); }
    public void restart() { directory.close();snapshots.close();open(); }
    public void nextFull() {
        jdbc.update("UPDATE local_energy_sync SET full_next_due_at=?",java.sql.Timestamp.from(clock.instant()));
        directory.synchronizeHistoryDue();
    }
    public static Map<String,Object> row(String meter,String id,String at,String value) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("equipmentId",meter);if(id!=null)row.put("id",id);row.put("readTime",at);row.put("epf",new java.math.BigDecimal(value));
        return row;
    }
    @Override public void close() { directory.close();snapshots.close();jdbc.execute("SHUTDOWN"); }
    public static final class MutableClock extends Clock {
        public Instant now = Instant.parse("2026-01-15T03:00:00Z");
        public void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
    }
    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Map<Key,State> states = new LinkedHashMap<>();
        @Override public Checkpoint startInstance(String instance) { return new Checkpoint(true,"fixture-epoch"); }
        @Override public boolean markClean(String instance) { return true; }
        @Override public synchronized Optional<State> read(Key key) { return Optional.ofNullable(states.get(key)); }
        @Override public synchronized boolean write(Key key,State state) { states.put(key,state);return true; }
        @Override public synchronized boolean writeIfCurrent(Key key,String generation,State state) {
            State current=states.get(key);
            if(generation==null?current!=null:current==null||!generation.equals(current.generation()))return false;
            return write(key,state);
        }
    }
}
