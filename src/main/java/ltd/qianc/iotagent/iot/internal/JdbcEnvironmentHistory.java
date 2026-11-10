package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceDirectoryBatch;
import ltd.qianc.iotagent.iot.DeviceObservations;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.EnvironmentHistory;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Site history shared only by identical authorized physical sensor sets; grants remain principal-local. */
public final class JdbcEnvironmentHistory implements EnvironmentHistory {
    private static final int PAGE_SIZE = 100;
    private static final String FACT_FIELDS = "source_record_id,source_id_missing,device_code,original_recorded_at,recorded_at,temp,humidity,pm25,pm10,pm1,invalid_values,source_quality_json,conflicted";
    private static final List<String> UPDATED_FIELDS = List.of("original_recorded_at","temp","humidity","pm25","pm10","pm1",
            "invalid_values","source_quality_json","conflicted","update_time","synchronized_at");
    private static final String PUBLISH = "INSERT INTO local_environment_samples(scope_key,sample_key," + FACT_FIELDS
            + ",credential_generation,synchronized_at,create_time,update_time) SELECT scope_key,sample_key," + FACT_FIELDS
            + ",?,?,?,? FROM local_environment_sample_batches WHERE run_id=? AND scope_key=? ON DUPLICATE KEY UPDATE "
            + "credential_generation=CASE WHEN local_environment_samples.synchronized_at>? "
            + "THEN local_environment_samples.credential_generation ELSE VALUES(credential_generation) END,"
            + String.join(",",UPDATED_FIELDS.stream().map(field -> field
                    + "=CASE WHEN local_environment_samples.synchronized_at>? "
                    + "THEN local_environment_samples." + field + " ELSE VALUES(" + field + ") END").toList());
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Function<RequestIdentity,DeviceDirectoryBatch> directory;
    private final Function<RequestIdentity,String> keys;
    private final DeviceObservations observations;
    private final Clock clock;

    JdbcEnvironmentHistory(JdbcTemplate jdbc,TransactionTemplate transactions,ObjectMapper mapper,
            Function<RequestIdentity,DeviceDirectoryBatch> directory,Function<RequestIdentity,String> keys,
            DeviceObservations observations,Clock clock) {
        this.jdbc=jdbc; this.transactions=transactions; this.mapper=mapper;
        this.directory=directory; this.keys=keys; this.observations=observations; this.clock=clock;
    }

    @Override public DeviceStateReadResult current(RequestIdentity identity,List<String> deviceIds) {
        try {
            Access access=authorize(identity);
            if(!environmentIds(access).containsAll(deviceIds))
                throw new BusinessException("DEVICE_OUTSIDE_SCOPE","环境设备不在当前授权范围内",403);
            DeviceStateReadResult result=observations.read(identity,deviceIds);
            Access current=authorize(identity);
            if(!access.generation().equals(current.generation()) || !environmentIds(current).containsAll(deviceIds)) throw preparing();
            return result;
        } catch(DataAccessException failure) { throw unavailable(); }
    }

    @Override public ObservationResponse read(ObservationRequest request) {
        if(request.operation()!=ObservationOperationEnum.ENVIRONMENT_HISTORY) throw invalidWindow();
        Map<String,String> parameters=request.parameters();
        Instant from=parse(parameters.getOrDefault("from","1970-01-01T00:00:00Z"));
        Instant to=parameters.containsKey("to")?parse(parameters.get("to")):clock.instant();
        String resolution=parameters.get("resolution");
        boolean trend="auto".equals(resolution);
        if(resolution!=null && !trend || trend && (parameters.containsKey("page") || !parameters.containsKey("from") || !parameters.containsKey("to"))) throw invalidWindow();
        if(trend) EnvironmentTrendProjection.validate(from,to);
        boolean fullWindow=trend || Boolean.parseBoolean(parameters.get("fullWindow"));
        int page=fullWindow?0:page(parameters.get("page"));
        if(from.isAfter(to)) throw invalidWindow();
        try {
            Access access=authorize(request.identity());
            Progress progress=progress(access.dataKey());
            if(progress==null || progress.fullSuccess()==null) {
                request(access,from,to);
                throw preparing();
            }
            String predicate="scope_key=? AND (recorded_at IS NULL OR (recorded_at>=? AND recorded_at<=?))";
            String sql="SELECT * FROM local_environment_samples WHERE "+predicate
                    +" ORDER BY CASE WHEN recorded_at IS NULL THEN 1 ELSE 0 END,recorded_at DESC,sample_key DESC";
            List<Sample> samples=fullWindow
                    ?jdbc.query(sql,this::sample,access.dataKey(),Timestamp.from(from),Timestamp.from(to))
                    :jdbc.query(sql+" LIMIT 100 OFFSET ?",this::sample,
                            access.dataKey(),Timestamp.from(from),Timestamp.from(to),(long)page*PAGE_SIZE);
            Long total=fullWindow?(long)samples.size():jdbc.queryForObject(
                    "SELECT COUNT(*) FROM local_environment_samples WHERE "+predicate,Long.class,
                    access.dataKey(),Timestamp.from(from),Timestamp.from(to));
            samples.sort(EnvironmentSamples.ORDER);
            Integer next=!fullWindow && (long)(page+1)*PAGE_SIZE<total?page+1:null;
            Map<String,Object> payload=trend ? EnvironmentTrendProjection.payload(samples,from,to)
                    : EnvironmentSamples.payload(samples,from.toString(),to.toString(),true,next,null);
            payload.put("totalElements",total);
            Access current=authorize(request.identity());
            Progress latest=progress(current.dataKey());
            if(!access.generation().equals(current.generation()) || !access.dataKey().equals(current.dataKey())
                    || latest==null || latest.version()!=progress.version()) throw preparing();
            payload.put("cache",cache(latest));
            return EnvironmentSamples.response(payload);
        } catch(DataAccessException failure) { throw unavailable(); }
    }

    void revalidate(RequestIdentity identity) { authorize(identity); }

    private Access authorize(RequestIdentity identity) {
        if(identity.source()!=IotSourceEnum.COMPANY) throw new BusinessException("HISTORY_SOURCE_UNAVAILABLE","环境历史仅支持公司来源",404);
        var batch=directory.apply(identity);
        if(batch.scope().devices().stream().noneMatch(device -> "environment".equals(device.kind())))
            throw new BusinessException("FORBIDDEN","当前公司范围没有环境观察授权",403);
        String key=keys.apply(identity);
        List<String> generations=jdbc.query("SELECT credential_generation FROM local_sync_scopes WHERE scope_key=? AND published_generation=credential_generation AND denial_status=0",
                (rs,row)->rs.getString(1),key);
        if(generations.isEmpty()) throw preparing();
        String dataKey=sharedKey(key,generations.getFirst());
        if(dataKey==null) throw preparing();
        return new Access(key,generations.getFirst(),dataKey,batch.scope());
    }

    private static java.util.Set<String> environmentIds(Access access) {
        var ids=new java.util.HashSet<String>();
        for(var device:access.scope().devices()) if("environment".equals(device.kind())) ids.add(device.id());
        return ids;
    }

    private Progress progress(String key) {
        List<Progress> rows=jdbc.query("SELECT * FROM local_environment_sync WHERE scope_key=?",
                (rs,row)->new Progress(rs.getLong("publication_version"),instant(rs,"current_success_at"),instant(rs,"full_success_at"),
                        rs.getString("current_failure_code")!=null || rs.getString("full_failure_code")!=null),key);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private void request(Access access,Instant from,Instant to) {
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("INSERT IGNORE INTO local_environment_requests(scope_key,credential_generation,request_key,range_from,range_to,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                access.dataKey(),access.generation(),LocalDataKeys.hash(from+"\u0000"+to),Timestamp.from(from),Timestamp.from(to),now,now);
    }

    private Map<String,Object> cache(Progress progress) {
        Instant success=progress.currentSuccess();
        if(success==null || progress.fullSuccess()!=null && progress.fullSuccess().isAfter(success)) success=progress.fullSuccess();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("status",success==null || progress.failed() || !clock.instant().isBefore(success.plusSeconds(60))?"stale":"fresh");
        result.put("updatedAt",success==null?null:success.toString());
        if(progress.failed()) result.put("lastError","环境采集未完成");
        return result;
    }

    boolean fullDue(String key,String generation) {
        String shared=sharedKey(key,generation);
        return shared!=null && jdbc.queryForList("SELECT id FROM local_environment_sync WHERE scope_key=? AND full_next_due_at>?",
                shared,Timestamp.from(clock.instant())).isEmpty();
    }

    String begin(String key,String generation,boolean full,Instant attempt) {
        return transactions.execute(tx -> {
            lockScope(key,generation);
            String shared=sharedKey(key,generation);
            if(shared==null) return null;
            Timestamp now=Timestamp.from(clock.instant());
            jdbc.update("INSERT IGNORE INTO local_environment_sync(scope_key,credential_generation,full_next_due_at,create_time,update_time) VALUES (?,?,?,?,?)",
                    shared,generation,now,now,now);
            Map<String,Object> previous=jdbc.queryForMap("SELECT * FROM local_environment_sync WHERE scope_key=? FOR UPDATE",shared);
            if(full) {
                int claimed=jdbc.update("UPDATE local_environment_sync SET credential_generation=?,full_attempt_at=?,full_state='RUNNING',"
                                +"full_failure_code=NULL,full_next_due_at=?,update_time=? WHERE scope_key=? AND full_next_due_at<=?",
                        generation,Timestamp.from(attempt),Timestamp.from(JdbcEnergyHistory.nextNight(clock.instant())),now,shared,now);
                return claimed==1?shared:null;
            }
            if(previous.get("full_success_at")==null) return null;
            Timestamp boundary=Timestamp.from(clock.instant().minusSeconds(60));
            int claimed=jdbc.update("UPDATE local_environment_sync SET credential_generation=?,current_attempt_at=?,current_failure_code=NULL,update_time=? "
                            +"WHERE scope_key=? AND (current_attempt_at IS NULL OR current_attempt_at<=?) "
                            +"AND (current_success_at IS NULL OR current_success_at<=?) AND full_success_at<=?",
                    generation,Timestamp.from(attempt),now,shared,boundary,boundary,boundary);
            return claimed==1?shared:null;
        });
    }

    Instant currentCutoff(String shared) {
        List<Instant> rows=jdbc.query("SELECT full_covered_until FROM local_environment_sync WHERE scope_key=?",
                (rs,row)->instant(rs,"full_covered_until"),shared);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private String sharedKey(String key,String generation) {
        List<String> realms=new ArrayList<>();
        List<ltd.qianc.iotagent.iot.AuthorizedDevice> sensors=jdbc.query(
                "SELECT s.gateway_instance,s.tenant_code,s.source,d.* FROM local_sync_scopes s JOIN local_company_devices d ON d.scope_key=s.scope_key "
                        +"WHERE s.scope_key=? AND s.credential_generation=? AND s.published_generation=? AND s.denial_status=0 AND d.device_kind='environment'",
                (rs,row)->{
                    realms.add(LocalDataKeys.observations(rs.getString("gateway_instance"),rs.getString("tenant_code"),rs.getString("source")));
                    com.fasterxml.jackson.databind.JsonNode metadata;
                    try { metadata=mapper.readTree(rs.getString("metadata_json")); }
                    catch(java.io.IOException malformed) { throw unavailable(); }
                    return new ltd.qianc.iotagent.iot.AuthorizedDevice(rs.getString("source_device_id"),rs.getString("device_kind"),
                            rs.getString("device_name"),rs.getString("install_address"),true,rs.getString("control_ref"),
                            rs.getString("office_device_ref"),rs.getString("hub_device_id"),rs.getString("smart_area_code"),List.of(),List.of(),
                            Map.of("code",metadata.path("code").asText(""),"companyAcDetailActionId",metadata.path("companyAcDetailActionId").asText("")));
                },key,generation,generation);
        return sensors.isEmpty()?null:LocalDataKeys.environmentHistory(realms.getFirst(),sensors);
    }

    void stage(String run,String key,Map<String,Sample> samples) {
        if(samples.isEmpty()) return;
        Timestamp now=Timestamp.from(clock.instant());
        List<Object[]> rows=new ArrayList<>(samples.size());
        for(Map.Entry<String,Sample> entry:samples.entrySet()) {
            Sample sample=entry.getValue();
            if(sample.recordedAt()!=null && !sample.recordedAt().equals(sample.recordedAt().truncatedTo(ChronoUnit.MICROS)))
                throw new IllegalArgumentException("Environment sampling time exceeds DATETIME(6)");
            rows.add(new Object[]{run,key,entry.getKey(),sample.sourceId(),sample.sourceIdMissing(),sample.deviceCode(),
                    sample.originalRecordedAt(),sample.recordedAt()==null?null:Timestamp.from(sample.recordedAt()),decimal(sample.temp()),decimal(sample.humidity()),
                    decimal(sample.pm25()),decimal(sample.pm10()),decimal(sample.pm1()),sample.invalidValues(),json(sample.sourceQuality()),sample.conflicted(),now,now});
        }
        jdbc.batchUpdate("INSERT INTO local_environment_sample_batches(run_id,scope_key,sample_key,"+FACT_FIELDS+",create_time,update_time) "
                +"VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE conflicted=TRUE,update_time=VALUES(update_time)",rows);
    }

    void publish(String run,String key,String generation,String shared,boolean full,Instant attempt,Runnable authorize) {
        transactions.executeWithoutResult(tx -> {
            lockScope(key,generation);
            if(!shared.equals(sharedKey(key,generation))) throw preparing();
            String attemptColumn=full?"full_attempt_at":"current_attempt_at";
            List<Instant> attempts=jdbc.query("SELECT "+attemptColumn+" FROM local_environment_sync WHERE scope_key=? FOR UPDATE",
                    (rs,row)->instant(rs,attemptColumn),shared);
            if(attempts.isEmpty() || !attempt.equals(attempts.getFirst())) throw preparing();
            authorize.run();
            Timestamp now=Timestamp.from(clock.instant());
            List<Object> parameters=new ArrayList<>();
            parameters.add(generation); parameters.add(now); parameters.add(now); parameters.add(now); parameters.add(run); parameters.add(shared);
            parameters.add(Timestamp.from(attempt));
            for(String ignored:UPDATED_FIELDS) parameters.add(Timestamp.from(attempt));
            jdbc.update(PUBLISH,parameters.toArray());
            if(full) {
                jdbc.update("UPDATE local_environment_sync SET publication_version=publication_version+1,full_success_at=?,full_covered_until=?,full_state='READY',full_failure_code=NULL,update_time=? WHERE scope_key=?",
                        now,Timestamp.from(attempt),now,shared);
                jdbc.update("UPDATE local_environment_requests SET state='READY',applied_at=?,update_time=? WHERE scope_key=?",now,now,shared);
            } else jdbc.update("UPDATE local_environment_sync SET publication_version=publication_version+1,current_success_at=?,current_failure_code=NULL,update_time=? WHERE scope_key=?",now,now,shared);
            authorize.run();
        });
    }

    void failed(String key,boolean full,Instant attempt) {
        if(key==null) return;
        Timestamp now=Timestamp.from(clock.instant()),started=Timestamp.from(attempt);
        if(full) jdbc.update("UPDATE local_environment_sync SET full_attempt_at=?,full_state='FAILED',full_failure_code='ENVIRONMENT_HISTORY_SYNC_UNAVAILABLE',full_next_due_at=?,update_time=? "
                        +"WHERE scope_key=? AND (full_attempt_at IS NULL OR full_attempt_at<=?)",
                started,Timestamp.from(clock.instant().plusSeconds(60)),now,key,started);
        else jdbc.update("UPDATE local_environment_sync SET current_attempt_at=?,current_failure_code='ENVIRONMENT_CURRENT_SYNC_UNAVAILABLE',update_time=? "
                +"WHERE scope_key=? AND (current_attempt_at IS NULL OR current_attempt_at<=?)",started,now,key,started);
    }

    void discard(String run) { jdbc.update("DELETE FROM local_environment_sample_batches WHERE run_id=?",run); }

    private void lockScope(String key,String generation) {
        if(jdbc.queryForList("SELECT id FROM local_sync_scopes WHERE scope_key=? AND credential_generation=? AND published_generation=? FOR UPDATE",key,generation,generation).isEmpty()) throw preparing();
    }

    private Sample sample(ResultSet rs,int row) throws SQLException {
        Object quality=null;
        String raw=rs.getString("source_quality_json");
        if(raw!=null) try { quality=mapper.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(raw); }
        catch(java.io.IOException malformed) { throw unavailable(); }
        return new Sample(rs.getString("source_record_id"),rs.getBoolean("source_id_missing"),rs.getString("device_code"),rs.getString("original_recorded_at"),
                instant(rs,"recorded_at"),rs.getBigDecimal("temp"),rs.getBigDecimal("humidity"),rs.getBigDecimal("pm25"),rs.getBigDecimal("pm10"),rs.getBigDecimal("pm1"),
                rs.getBoolean("invalid_values"),quality,rs.getBoolean("conflicted"));
    }

    private String json(Object value) {
        if(value==null) return null;
        try { return mapper.writeValueAsString(value); }
        catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid environment quality",invalid); }
    }
    private static BigDecimal decimal(BigDecimal value) {
        if(value==null) return null;
        BigDecimal result=value.setScale(30,RoundingMode.UNNECESSARY);
        if(result.precision()>65) throw new IllegalArgumentException("Environment value exceeds DECIMAL(65,30)");
        return result;
    }
    private static Instant parse(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch(RuntimeException invalid) { throw invalidWindow(); }
    }
    private static int page(String value) {
        if(value==null) return 0;
        try { int page=Integer.parseInt(value); if(page<0 || page==Integer.MAX_VALUE) throw invalidWindow(); return page; }
        catch(NumberFormatException invalid) { throw invalidWindow(); }
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant(); }
    private static BusinessException invalidWindow() { return new BusinessException("INVALID_HISTORY_WINDOW","环境历史起止窗口或页号无效",400); }
    private static BusinessException preparing() { return new BusinessException("ENVIRONMENT_HISTORY_PREPARING","环境历史尚未完整同步，请稍后重试",503); }
    private static BusinessException unavailable() { return new BusinessException("ENVIRONMENT_HISTORY_UNAVAILABLE","本地环境数据当前不可用，请稍后重试",503); }
    private record Access(String key,String generation,String dataKey,ltd.qianc.iotagent.iot.AuthorizedDeviceScope scope) { }
    private record Progress(long version,Instant currentSuccess,Instant fullSuccess,boolean failed) { }
}
