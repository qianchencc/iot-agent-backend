package com.ehe.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class EnvironmentHistoryBehaviorTest {
    @Test void newlyAuthorizedViewerUsesExistingDeviceFactsWithoutResamplingOrRefreshingTheirTimes() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish();
            var original=f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()));
            f.currentResult=new DeviceStateReadResult(Map.of(EnvironmentHistoryFixture.SENSOR.id(),Map.of("temp",new BigDecimal("99"))),f.clock.instant().toString());
            var colleague=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(colleague);
            f.directory.synchronizeDue();
            var reused=f.history().current(colleague,List.of(EnvironmentHistoryFixture.SENSOR.id()));
            assertEquals(original.states(),reused.states());
            assertEquals(original.observedAtByDevice(),reused.observedAtByDevice());
            assertEquals(original.cache().get("updatedAt"),reused.cache().get("updatedAt"));
            assertEquals(1,f.stateCalls.get(),"同一新鲜设备事实不应因另一个已授权用户而重采");
        }
    }

    @Test void equivalentAuthorizedSensorScopesReusePublishedHistoryWithoutAnotherCollection() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("shared","fixture-env-code","2020-01-01T00:00:00Z","25"));
            f.publish();
            var original=f.history().read(f.request());
            int calls=f.sourceCalls.get();
            var colleague=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(colleague);
            f.directory.synchronizeDue();
            var reused=f.history().read(f.request(colleague,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0));
            assertEquals(samples(original),samples(reused));
            assertEquals(original.payload().get("cache"),reused.payload().get("cache"));
            f.directory.synchronizeHistoryDue();
            assertEquals(calls,f.sourceCalls.get(),"相同测点范围的已发布历史只同步一份");
        }
    }

    @Test void aNewViewerCollectsOnlyUncoveredDevicesRatherThanRefreshingSharedOnes() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish();
            var extra=new AuthorizedDevice("fixture-additional-sensor","environment","额外测点","",true);
            f.devices=List.of(EnvironmentHistoryFixture.SENSOR,extra);
            f.currentResult=new DeviceStateReadResult(Map.of(EnvironmentHistoryFixture.SENSOR.id(),Map.of("temp",new BigDecimal("99")),
                    extra.id(),Map.of("temp",new BigDecimal("35"))),f.clock.instant().toString());
            var colleague=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(colleague); f.directory.synchronizeDue();
            var result=f.history().current(colleague,List.of(EnvironmentHistoryFixture.SENSOR.id(),extra.id()));
            assertEquals(0,new BigDecimal("26").compareTo((BigDecimal)((Map<?,?>)result.states().get(EnvironmentHistoryFixture.SENSOR.id())).get("temp")));
            assertEquals(0,new BigDecimal("35").compareTo((BigDecimal)((Map<?,?>)result.states().get(extra.id())).get("temp")));
        }
    }

    @Test void legacyFactsMigrateWithoutResamplingAndSurviveTheCollectorsRetirement() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("legacy","fixture-env-code","2020-01-01T00:00:00Z","25"));
            f.register(f.identity()); f.directory.synchronizeDue();
            assertThrows(BusinessException.class,()->f.history().read(f.request()));
            f.directory.synchronizeHistoryDue();
            var originalCurrent=f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()));
            var originalHistory=f.history().read(f.request());
            int calls=f.sourceCalls.get();
            String actor=f.jdbc.queryForObject("SELECT scope_key FROM local_sync_scopes",String.class);
            String oldDevice=f.jdbc.queryForObject("SELECT device_key FROM local_company_devices",String.class);
            f.jdbc.update("UPDATE local_device_states SET scope_key=?,device_key=?",actor,oldDevice);
            f.jdbc.update("DELETE FROM local_device_state_sync WHERE state='DISPATCHED'");
            f.jdbc.update("UPDATE local_device_state_sync SET scope_key=?",actor);
            for(String table:List.of("local_environment_samples","local_environment_sync","local_environment_requests"))
                f.jdbc.update("UPDATE "+table+" SET scope_key=?",actor);
            f.jdbc.update("DELETE FROM storage_migrations WHERE migration_key='local-shared-observations-v2'");
            f.clock.advance(10);
            new com.ehe.iotagent.schema.SchemaMigrations(f.jdbc.getDataSource()).ensureLocalCompanyDirectory();
            new com.ehe.iotagent.schema.SchemaMigrations(f.jdbc.getDataSource()).ensureLocalCompanyDirectory();
            var colleague=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(colleague); f.directory.synchronizeDue();
            f.jdbc.update("DELETE FROM local_sync_scopes WHERE scope_key=?",actor);
            f.unavailable=true; f.restart();
            var current=f.history().current(colleague,List.of(EnvironmentHistoryFixture.SENSOR.id()));
            var history=f.history().read(f.request(colleague,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0));
            assertEquals(originalCurrent.states(),current.states());
            assertEquals(originalCurrent.cache().get("updatedAt"),current.cache().get("updatedAt"));
            assertEquals(samples(originalHistory),samples(history));
            assertEquals(originalHistory.payload().get("cache"),history.payload().get("cache"));
            assertEquals(calls,f.sourceCalls.get());
        }
    }

    @Test void legacyStateWithoutEvidenceOfTheCurrentBindingRemainsUnavailableRatherThanRelabeled() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish();
            String actor=f.jdbc.queryForObject("SELECT scope_key FROM local_sync_scopes",String.class);
            String oldDevice=f.jdbc.queryForObject("SELECT device_key FROM local_company_devices",String.class);
            f.jdbc.update("UPDATE local_device_states SET scope_key=?,device_key=?",actor,oldDevice);
            f.jdbc.update("DELETE FROM local_device_state_sync WHERE state='DISPATCHED'");
            f.jdbc.update("UPDATE local_device_state_sync SET scope_key=?",actor);
            f.clock.advance(10);
            f.jdbc.update("UPDATE local_company_devices SET hub_device_id='fixture-rebound-hub'");
            f.jdbc.update("UPDATE local_sync_scopes SET last_success_at=?",java.sql.Timestamp.from(f.clock.instant()));
            f.jdbc.update("DELETE FROM storage_migrations WHERE migration_key='local-shared-observations-v2'");
            new com.ehe.iotagent.schema.SchemaMigrations(f.jdbc.getDataSource()).ensureLocalCompanyDirectory();
            f.unavailable=true;
            assertEquals(503,assertThrows(BusinessException.class,
                    ()->f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()))).httpStatus());
            assertEquals(1,f.stateCalls.get(),"迁移与普通读取不允许现场补采设备");
        }
    }

    @Test void changedPhysicalBindingCannotBorrowAnExistingSensorsObservation() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish();
            var original=f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()));
            f.devices=List.of(new AuthorizedDevice(EnvironmentHistoryFixture.SENSOR.id(),"environment","重绑定测点","",true,
                    null,null,"fixture-different-hub",null,List.of(),List.of(),Map.of("code","fixture-different-code")));
            f.currentUnavailable=true;
            var colleague=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(colleague); f.directory.synchronizeDue();
            assertEquals("DEVICE_STATE_UNAVAILABLE",assertThrows(BusinessException.class,
                    ()->f.history().current(colleague,List.of(EnvironmentHistoryFixture.SENSOR.id()))).code());
            assertEquals(original.states(),f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id())).states());
        }
    }

    @Test void identicalDeviceIdsFromAnotherGatewayNeverShareMeasurements() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("original",null,"2020-01-01T00:00:00Z","25"));
            f.publish();
            f.gatewayBase="https://another-environment-fixture.example.invalid";
            f.currentResult=new DeviceStateReadResult(Map.of(EnvironmentHistoryFixture.SENSOR.id(),Map.of("temp",new BigDecimal("99"))),f.clock.instant().toString());
            f.restart(); f.register(f.identity()); f.directory.synchronizeDue();
            var current=f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()));
            assertEquals(0,new BigDecimal("99").compareTo((BigDecimal)((Map<?,?>)current.states().get(EnvironmentHistoryFixture.SENSOR.id())).get("temp")));
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,()->f.history().read(f.request())).code());
            f.gatewayBase=EnvironmentHistoryFixture.GATEWAY; f.restart();
            var original=f.history().current(f.identity(),List.of(EnvironmentHistoryFixture.SENSOR.id()));
            assertEquals(0,new BigDecimal("26").compareTo((BigDecimal)((Map<?,?>)original.states().get(EnvironmentHistoryFixture.SENSOR.id())).get("temp")));
        }
    }

    @Test void anUnvisitedAncientWindowSurvivesRestartAndSharesLocalCurrentReadingWithDashboard() {
        try(var f=new EnvironmentHistoryFixture()) {
            String exact="12345678901234567890.1234567890123456789";
            f.rows=List.of(EnvironmentHistoryFixture.row("fixture-ancient","fixture-env-code","2020-01-01T00:00:00Z",exact));
            f.register(f.identity());
            f.directory.synchronizeDue();
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,()->f.history().read(f.request())).code());
            f.restart();
            f.directory.synchronizeHistoryDue();
            ObservationResponse history=f.composed().read(f.request()).block();
            assertEquals(ObservationStatusEnum.AVAILABLE,history.status());
            assertEquals(0,((BigDecimal)values(samples(history).getFirst()).get("temp")).compareTo(new BigDecimal(exact)));
            assertEquals("2020-01-01T00:00:00Z",history.payload().get("observedAt"));
            int calls=f.sourceCalls.get();
            f.unavailable=true;
            f.restart();
            assertEquals(samples(history),samples(f.composed().read(f.request()).block()));
            var dashboard=f.composed().read(new ObservationRequest(f.identity(),ObservationOperationEnum.DASHBOARD,Map.of("section","environment"))).block();
            Map<?,?> row=(Map<?,?>)((List<?>)dashboard.payload().get("environment")).getFirst();
            assertEquals(0,((BigDecimal)((Map<?,?>)row.get("reading")).get("temp")).compareTo(new BigDecimal("26")));
            assertEquals(f.clock.instant().toString(),row.get("observedAt"));
            assertFalse(row.containsKey("hubDeviceId"));
            assertEquals(calls,f.sourceCalls.get());
        }
    }

    @Test void onlyACompleteEmptyStreamIsEmptyAndWindowBoundsExcludeOutsidePeaks() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish();
            ObservationResponse empty=f.history().read(f.request());
            assertEquals(ObservationStatusEnum.EMPTY,empty.status());
            assertTrue(empty.coverageComplete());
            assertNull(metric(empty,"temp").get("peak"));
            f.rows=List.of(EnvironmentHistoryFixture.row("before",null,"2019-12-31T23:59:59Z","999"),
                    EnvironmentHistoryFixture.row("start",null,"2020-01-01T00:00:00Z","10"),
                    EnvironmentHistoryFixture.row("end",null,"2020-01-02T00:00:00Z","20"),
                    EnvironmentHistoryFixture.row("after",null,"2020-01-02T00:00:01Z","999"));
            f.clock.advance(61); f.nextFull();
            var result=f.history().read(f.request());
            assertEquals(Set.of("start","end"),samples(result).stream().map(row->row.get("id")).collect(Collectors.toSet()));
            assertEquals(0,((BigDecimal)metric(result,"temp").get("min")).compareTo(new BigDecimal("10")));
            assertEquals(0,((BigDecimal)((Map<?,?>)metric(result,"temp").get("peak")).get("value")).compareTo(new BigDecimal("20")));
        }
    }

    @Test void unknownTimesAndInvalidValuesRemainPartialAndCannotCreatePeaksOrFakeAssociations() {
        try(var f=new EnvironmentHistoryFixture()) {
            var undated=EnvironmentHistoryFixture.row("undated",null,"invalid-time","999");
            undated.put("quality","unverified");
            var invalid=EnvironmentHistoryFixture.row("invalid-metric","unmatched-code","2020-01-01T01:00:00Z","30");
            invalid.put("pm25","bad");
            f.rows=List.of(EnvironmentHistoryFixture.row("known","fixture-env-code","2020-01-01T00:00:00Z","25"),undated,invalid);
            f.publish();
            var result=f.history().read(f.request());
            assertEquals(ObservationStatusEnum.PARTIAL,result.status());
            assertFalse(result.coverageComplete());
            assertNull(result.payload().get("observedAt"));
            var unknown=samples(result).stream().filter(row->"undated".equals(row.get("id"))).findFirst().orElseThrow();
            assertNull(unknown.get("recordedAt"));
            assertEquals("invalid-time",unknown.get("originalRecordedAt"));
            assertEquals("unverified",((Map<?,?>)unknown.get("quality")).get("source"));
            assertEquals(0,((BigDecimal)((Map<?,?>)metric(result,"temp").get("peak")).get("value")).compareTo(new BigDecimal("30")));
            assertEquals(1,metric(result,"pm25").get("count"));
            assertEquals("site",result.payload().get("attribution"));
            assertTrue(samples(result).stream().noneMatch(row->row.containsKey("deviceId") || row.containsKey("floor")));
        }
    }

    @Test void stableCompositeIdentitiesAreIdempotentAndCorrectionsUpdateOnlyTheirRecord() {
        try(var f=new EnvironmentHistoryFixture()) {
            String at="2020-01-01T00:00:00Z";
            var rows=new ArrayList<Map<String,Object>>();
            rows.add(EnvironmentHistoryFixture.row("a","code-a",at,"10"));
            rows.add(EnvironmentHistoryFixture.row("b","code-a",at,"20"));
            rows.add(EnvironmentHistoryFixture.row("a","code-a","2020-01-01T01:00:00Z","30"));
            rows.add(EnvironmentHistoryFixture.row("a","code-b",at,"40"));
            rows.add(EnvironmentHistoryFixture.row(null,null,at,"50"));
            rows.add(EnvironmentHistoryFixture.row(at+":site",null,at,"60"));
            rows.add(EnvironmentHistoryFixture.row(null,"site",at,"70"));
            f.rows=rows; f.publish();
            var first=f.history().read(f.request());
            assertEquals(7L,first.payload().get("totalElements"));
            f.clock.advance(61); f.nextFull();
            assertEquals(samples(first),samples(f.history().read(f.request())));
            rows.set(0,EnvironmentHistoryFixture.row("a","code-a",at,"11"));
            f.rows=rows; f.clock.advance(61); f.nextFull();
            var result=f.history().read(f.request());
            assertEquals(7L,result.payload().get("totalElements"));
            Set<Integer> values=samples(result).stream().map(row->((BigDecimal)values(row).get("temp")).intValueExact()).collect(Collectors.toSet());
            assertEquals(Set.of(11,20,30,40,50,60,70),values);
        }
    }

    @Test void identicalDuplicatesCoalesceButConflictingValuesAreNotCalculated() {
        try(var f=new EnvironmentHistoryFixture()) {
            var row=EnvironmentHistoryFixture.row("same","code-a","2020-01-01T00:00:00Z","10");
            f.rows=List.of(row,new java.util.LinkedHashMap<>(row)); f.publish();
            assertEquals(ObservationStatusEnum.AVAILABLE,f.history().read(f.request()).status());
            f.rows=List.of(row,EnvironmentHistoryFixture.row("same","code-a","2020-01-01T00:00:00Z","20"));
            f.clock.advance(61); f.nextFull();
            var result=f.history().read(f.request());
            assertEquals(ObservationStatusEnum.PARTIAL,result.status());
            assertTrue((Boolean)((Map<?,?>)samples(result).getFirst().get("quality")).get("conflicted"));
            assertNull(metric(result,"temp").get("peak"));
        }
    }

    @Test void aFailedLaterPageCannotPublishCorrectionsOrAdvanceCoverage() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=denseRows("100"); f.publish();
            var first=f.history().read(f.request());
            var success=((Map<?,?>)first.payload().get("cache")).get("updatedAt");
            f.rows=denseRows("200"); f.failPage=1; f.clock.advance(61); f.nextFull();
            var retained=f.history().read(f.request());
            assertEquals(samples(first),samples(retained));
            assertEquals(success,((Map<?,?>)retained.payload().get("cache")).get("updatedAt"));
            assertEquals("stale",((Map<?,?>)retained.payload().get("cache")).get("status"));
            f.failPage=-1; f.clock.advance(61); f.nextFull();
            var corrected=f.history().read(f.request());
            assertEquals(0,((BigDecimal)values(samples(corrected).getFirst()).get("temp")).compareTo(new BigDecimal("200")));
            var last=f.history().read(f.request(f.identity(),"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",1));
            assertEquals("fixture-row-0",samples(last).getFirst().get("id"));
            assertEquals(0,((BigDecimal)values(samples(last).getFirst()).get("temp")).compareTo(new BigDecimal("200")));
        }
    }

    @Test void disappearingDirectoryPointsRetainHistoryButCannotBypassCurrentAuthorization() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("retained",null,"2020-01-01T00:00:00Z","25")); f.publish();
            var original=samples(f.history().read(f.request()));
            f.devices=List.of(); f.clock.advance(61); f.directory.synchronizeDue();
            assertEquals(403,assertThrows(BusinessException.class,()->f.history().read(f.request())).httpStatus());
            f.devices=List.of(EnvironmentHistoryFixture.SENSOR); f.rows=List.of(); f.clock.advance(61); f.directory.synchronizeDue();
            assertEquals(original,samples(f.history().read(f.request())));
        }
    }

    @Test void sharedHistoryRetainsTenantSourceAndSensorSetIsolationAcrossCredentialRotation() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("original",null,"2020-01-01T00:00:00Z","25")); f.publish();
            var original=samples(f.history().read(f.request()));
            var tenant=f.identity("Bearer fixture-other","fixture-other-tenant");
            f.register(tenant); f.directory.synchronizeDue();
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,
                    ()->f.history().read(f.request(tenant,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0))).code());
            var office=new RequestIdentity(f.identity().authorization(),f.identity().tenantCode(),IotSourceEnum.OFFICE,f.identity().ownerKey());
            assertEquals(404,assertThrows(BusinessException.class,()->f.history().read(f.request(office,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0))).httpStatus());
            var rotated=f.identity("Bearer fixture-rotated","fixture-tenant");
            f.register(rotated);
            assertEquals("IOT_SCOPE_PREPARING",assertThrows(BusinessException.class,
                    ()->f.history().read(f.request(rotated,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0))).code());
            f.directory.synchronizeDue();
            assertEquals(original,samples(f.history().read(f.request(rotated,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0))));
            f.devices=List.of(new AuthorizedDevice("fixture-different-sensor","environment","其他授权测点","",true));
            var restricted=f.identity("Bearer fixture-other","fixture-tenant");
            f.register(restricted); f.directory.synchronizeDue();
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,
                    ()->f.history().read(f.request(restricted,"2020-01-01T00:00:00Z","2020-01-02T00:00:00Z",0))).code());
        }
    }

    @Test void aSourceDenialAndDatabaseFailureCannotFallBackToRetainedFactsOrCustomerData() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("original",null,"2020-01-01T00:00:00Z","25")); f.publish();
            f.denied=new GatewayAuthorizationException(403); f.clock.advance(61); f.nextFull();
            assertEquals(403,assertThrows(BusinessException.class,()->f.history().read(f.request())).httpStatus());
        }
        try(var f=new EnvironmentHistoryFixture()) {
            f.publish(); int calls=f.sourceCalls.get();
            f.jdbc.execute("DROP TABLE local_environment_samples");
            assertEquals("ENVIRONMENT_HISTORY_UNAVAILABLE",assertThrows(BusinessException.class,()->f.history().read(f.request())).code());
            assertEquals(calls,f.sourceCalls.get());
        }
    }

    @Test void anExplicitMissingMetricIsNeitherZeroNorCompleteQuality() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("start",null,"2020-01-01T00:00:00Z","10"),
                    EnvironmentHistoryFixture.row("gap",null,"2020-01-01T01:00:00Z",null),
                    EnvironmentHistoryFixture.row("end",null,"2020-01-01T02:00:00Z","20"));
            f.publish();
            var result=f.history().read(f.request());
            assertEquals(ObservationStatusEnum.PARTIAL,result.status());
            assertEquals(1,((Map<?,?>)result.payload().get("coverage")).get("missingValueSamples"));
            assertEquals(2,metric(result,"temp").get("count"));
            assertEquals(0,((BigDecimal)metric(result,"temp").get("min")).compareTo(new BigDecimal("10")));
            assertNull(values(samples(result).stream().filter(row->"gap".equals(row.get("id"))).findFirst().orElseThrow()).get("temp"));
        }
    }

    @Test void nightlyCollectionCatchesUpAfterDowntimeAndDoesNotRepeatSuccessfulWork() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("original",null,"2020-01-01T00:00:00Z","25"));
            f.publish();
            int before=f.sourceCalls.get();
            f.clock.now=Instant.parse("2026-01-15T18:59:00Z");
            f.directory.synchronizeHistoryDue();
            assertEquals(before,f.sourceCalls.get());
            f.clock.advance(60);
            f.directory.synchronizeHistoryDue();
            assertEquals(before+1,f.sourceCalls.get());
            f.restart();
            f.clock.now=Instant.parse("2026-01-18T20:00:00Z");
            f.directory.synchronizeHistoryDue();
            assertEquals(before+2,f.sourceCalls.get());
            f.directory.synchronizeHistoryDue();
            assertEquals(before+2,f.sourceCalls.get());
        }
    }

    @Test void inconsistentTotalsAndUnrepresentableValuesCannotCertifyEmptyCoverage() {
        try(var f=new EnvironmentHistoryFixture()) {
            f.pageOverride=page -> "{\"code\":200,\"content\":{\"last\":true,\"totalElements\":2,\"content\":[{\"id\":\"bad-page\",\"createdAt\":\"2020-01-01T00:00:00Z\",\"tem\":25}]}}";
            f.publish();
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,()->f.history().read(f.request())).code());
            f.pageOverride=null;
            f.rows=List.of(EnvironmentHistoryFixture.row("oversized",null,"2020-01-01T00:00:00Z","10000000000000000000000000000000000000000"));
            f.clock.advance(61); f.nextFull();
            assertEquals("ENVIRONMENT_HISTORY_PREPARING",assertThrows(BusinessException.class,()->f.history().read(f.request())).code());
        }
    }

    @Test void aSlowFullScanCannotReplaceTheNewerCurrentSample() throws Exception {
        try(var f=new EnvironmentHistoryFixture()) {
            f.rows=List.of(EnvironmentHistoryFixture.row("original","fixture-env-code","2020-01-01T00:00:00Z","100"));
            f.publish();
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            f.clock.advance(61);
            f.pageOverride=page -> {
                boolean historical=Thread.currentThread().getName().equals("fixture-environment-full");
                if(historical) {
                    entered.countDown();
                    try {
                        if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fixture scan gate timed out");
                    } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                }
                return "{\"code\":200,\"content\":{\"number\":0,\"last\":true,\"totalElements\":1,\"content\":[{\"id\":\"original\",\"deviceCode\":\"fixture-env-code\",\"createdAt\":\"2020-01-01T00:00:00Z\",\"tem\":"+(historical?"100":"200")+"}]}}";
            };
            var worker=new Thread(f::nextFull,"fixture-environment-full");
            worker.start();
            try {
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                f.clock.advance(1);
                f.directory.synchronizeDue();
            } finally {
                release.countDown();
                worker.join(10_000);
            }
            assertFalse(worker.isAlive());
            assertEquals("READY",f.jdbc.queryForObject("SELECT full_state FROM local_environment_sync",String.class));
            assertEquals(0,((BigDecimal)values(samples(f.history().read(f.request())).getFirst()).get("temp")).compareTo(new BigDecimal("200")));
        }
    }

    private static List<Map<?,?>> samples(ObservationResponse result) { return ((List<?>)result.payload().get("samples")).stream().<Map<?,?>>map(row->(Map<?,?>)row).toList(); }
    private static Map<?,?> values(Map<?,?> row) { return (Map<?,?>)row.get("values"); }
    private static Map<?,?> metric(ObservationResponse result,String key) {
        return ((List<?>)result.payload().get("metrics")).stream().map(row->(Map<?,?>)row).filter(row->key.equals(row.get("key"))).findFirst().orElseThrow();
    }
    private static List<Map<String,Object>> denseRows(String value) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int index=0;index<=100;index++) rows.add(EnvironmentHistoryFixture.row("fixture-row-"+index,"fixture-env-code",Instant.parse("2020-01-01T00:00:00Z").plusSeconds(index*60L).toString(),value));
        return rows;
    }
}
