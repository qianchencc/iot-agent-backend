package ltd.qianc.iotagent.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

/** Public production entry points, real JDBC and synthetic source protocol only; no listening ports. */
public final class EnvironmentHistoryFixture implements AutoCloseable {
    public static final String GATEWAY="https://environment-fixture.example.invalid";
    public static final AuthorizedDevice SENSOR=new AuthorizedDevice("fixture-env-device","environment","测试环境测点","测试位置",true,
            null,null,"fixture-private-hub",null,List.of(),List.of(),Map.of("code","fixture-env-code"));
    public final EnergyHistoryFixture.MutableClock clock=new EnergyHistoryFixture.MutableClock();
    public final JdbcTemplate jdbc;
    public final TransactionTemplate transactions;
    public final ObjectMapper mapper=new ObjectMapper();
    public final AtomicInteger sourceCalls=new AtomicInteger();
    public final AtomicInteger stateCalls=new AtomicInteger();
    public String gatewayBase=GATEWAY;
    public volatile List<AuthorizedDevice> devices=List.of(SENSOR);
    public volatile List<Map<String,Object>> rows=List.of();
    public volatile DeviceStateReadResult currentResult;
    public volatile boolean unavailable;
    public volatile boolean currentUnavailable;
    public volatile int failPage=-1;
    public volatile GatewayAuthorizationException denied;
    public volatile java.util.function.IntFunction<String> pageOverride;
    public DeviceDirectory directory;
    public IotAdapter ordinary;
    private final MemoryBarrier barrier=new MemoryBarrier();
    private ReadSnapshots snapshots;

    private final IotAdapter gateway=new IotAdapter() {
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            if(unavailable) return Mono.error(new IllegalStateException("synthetic directory source offline"));
            return Mono.just(AuthorizedDeviceScope.of(identity,identity.source(),devices));
        }
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
            return Mono.just(AuthorizedDeviceScope.of(identity,identity.source(),List.of()));
        }
        @Override public Mono<DeviceStateReadResult> collectCurrentStates(RequestIdentity identity,AuthorizedDeviceScope scope) {
            stateCalls.incrementAndGet();
            if(unavailable || currentUnavailable) return Mono.error(new IllegalStateException("synthetic current source offline"));
            if(currentResult!=null) return Mono.just(currentResult);
            Map<String,Object> states=new LinkedHashMap<>();
            if(scope.devices().stream().anyMatch(device -> device.id().equals(SENSOR.id()))) states.put(SENSOR.id(),
                    Map.of("temp",new BigDecimal("26"),"humidity",50,"pm25",7,"pm101",20,"pm10",4,"source","device"));
            return Mono.just(new DeviceStateReadResult(states,clock.instant().toString()));
        }
    };
    private final ObservationAdapter source=ObservationAdapterFactory.webClient(
            org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
                if(request.url().getPath().equals("/energy-history")) return response("{\"code\":200,\"content\":{\"content\":[],\"last\":true,\"totalElements\":0}}");
                sourceCalls.incrementAndGet();
                if(denied!=null) return Mono.error(denied);
                if(unavailable) return Mono.error(new IllegalStateException("synthetic environment source offline"));
                var query=org.springframework.web.util.UriComponentsBuilder.fromUri(request.url()).build().getQueryParams();
                if(query.containsKey("startDate") || query.containsKey("endDate") || query.containsKey("deviceCode"))
                    throw new AssertionError("collector must use the unfiltered environment stream");
                int page=Integer.parseInt(query.getFirst("page"));
                if(page==failPage) return Mono.error(new IllegalStateException("synthetic environment page failed"));
                try {
                    if(pageOverride!=null) return response(pageOverride.apply(page));
                    List<Map<String,Object>> ordered=rows.stream().sorted(Comparator.comparing(
                            row -> String.valueOf(row.get("createdAt")),Comparator.reverseOrder())).toList();
                    int first=Math.min(page*100,ordered.size()),last=Math.min(first+100,ordered.size());
                    return response(mapper.writeValueAsString(Map.of("code",200,"content",Map.of("content",ordered.subList(first,last),
                            "number",page,"last",last==ordered.size(),"totalElements",ordered.size(),"totalPages",(ordered.size()+99)/100))));
                } catch(java.io.IOException failure) { return Mono.error(failure); }
            }).build(),new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENVIRONMENT_HISTORY,"/environment-history",
                    ObservationOperationEnum.ENERGY_HISTORY,"/energy-history")),java.time.Duration.ofSeconds(1),IotCallPolicy.passThrough());

    public EnvironmentHistoryFixture() { this(database()); }
    public EnvironmentHistoryFixture(javax.sql.DataSource database) {
        jdbc=new JdbcTemplate(database);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(database));
        new SchemaMigrations(database).ensureLocalCompanyDirectory();
        // H2 quotes bound JSON strings; MySQL parses them. Use text only inside this H2 fixture.
        if(database instanceof JdbcDataSource) {
            jdbc.execute("ALTER TABLE local_company_devices ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
            jdbc.execute("ALTER TABLE local_device_states ALTER COLUMN extensions_json VARCHAR(1000000) NOT NULL");
            jdbc.execute("ALTER TABLE local_environment_samples ALTER COLUMN source_quality_json VARCHAR(1000000) NULL");
            jdbc.execute("ALTER TABLE local_environment_sample_batches ALTER COLUMN source_quality_json VARCHAR(1000000) NULL");
        }
        open();
    }
    private static JdbcDataSource database() {
        var database=new JdbcDataSource();
        database.setURL("jdbc:h2:mem:environment_history_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return database;
    }
    private void open() {
        snapshots=ReadSnapshots.create(clock,Runnable::run,Runnable::run,null,barrier,mapper,ReadSnapshots.Settings.defaults());
        directory=DeviceDirectory.jdbc(jdbc,transactions,mapper,gateway,source,
                presented -> principal(presented.authorization(),presented.tenantCode()),snapshots,barrier,gatewayBase,"cd".repeat(32),clock, request -> List.of(), 20);
        DeviceObservations observations=DeviceObservations.jdbc(jdbc,transactions,mapper,directory,gatewayBase,clock);
        ordinary=IotAdapterFactory.localReads(gateway,()->directory,()->observations,IotAdapterFactory.candidateSets(jdbc,mapper));
    }
    public RequestIdentity identity() { return identity("Bearer fixture-environment","fixture-tenant"); }
    public RequestIdentity identity(String token,String tenant) { return new RequestIdentity(token,tenant,IotSourceEnum.COMPANY,principal(token,tenant).ownerKey()); }
    private static PrincipalInfo principal(String token,String tenant) {
        String principal=token.startsWith("Bearer fixture-member-")?token:token.endsWith("other")?"fixture-other-principal":"fixture-principal";
        return new PrincipalInfo(token,tenant,principal,"fixture-realm");
    }
    public EnvironmentHistory history() { return directory.environmentHistory(); }
    public ObservationAdapter composed() {
        return ObservationAdapterFactory.composed(request -> Mono.error(new AssertionError("ordinary environment read contacted source")),
                ordinary,identity -> Mono.just(Map.of()),null,history(),clock);
    }
    public void register(RequestIdentity identity) {
        try { directory.loadBatch(identity); }
        catch(BusinessException failure) { if(!"IOT_SCOPE_PREPARING".equals(failure.code())) throw failure; }
    }
    public void publish() { register(identity()); directory.synchronizeDue(); directory.synchronizeHistoryDue(); }
    public void nextFull() { jdbc.update("UPDATE local_environment_sync SET full_next_due_at=?",java.sql.Timestamp.from(clock.instant())); directory.synchronizeHistoryDue(); }
    public void restart() { directory.close(); snapshots.close(); open(); }
    public ObservationRequest request() { return request(identity(),"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0); }
    public ObservationRequest request(RequestIdentity identity,String from,String to,int page) {
        return new ObservationRequest(identity,ObservationOperationEnum.ENVIRONMENT_HISTORY,Map.of("from",from,"to",to,"page",Integer.toString(page)));
    }
    public static Map<String,Object> row(String id,String deviceCode,String at,String temp) {
        Map<String,Object> row=new LinkedHashMap<>();
        if(id!=null) row.put("id",id); row.put("deviceCode",deviceCode); row.put("createdAt",at);
        row.put("tem",temp==null?null:new BigDecimal(temp)); row.put("hum",50); row.put("pm25",7); row.put("pm101",20); row.put("pm10",4);
        return row;
    }
    private static Mono<org.springframework.web.reactive.function.client.ClientResponse> response(String json) {
        return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                .header("Content-Type","application/json").body(json).build());
    }
    @Override public void close() {
        directory.close(); snapshots.close();
        if(jdbc.getDataSource() instanceof JdbcDataSource) jdbc.execute("SHUTDOWN");
    }
    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Map<Key,State> states=new LinkedHashMap<>();
        @Override public Checkpoint startInstance(String instance) { return new Checkpoint(true,"fixture-epoch"); }
        @Override public boolean markClean(String instance) { return true; }
        @Override public synchronized Optional<State> read(Key key) { return Optional.ofNullable(states.get(key)); }
        @Override public synchronized boolean write(Key key,State state) { states.put(key,state); return true; }
        @Override public synchronized boolean writeIfCurrent(Key key,String generation,State state) {
            State current=states.get(key);
            if(generation==null?current!=null:current==null || !generation.equals(current.generation())) return false;
            return write(key,state);
        }
    }
}
