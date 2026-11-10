package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class EnergyHistoryBehaviorTest {
    private static final String METER = EnergyHistoryFixture.METER.id();
    private static final Instant FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-14T23:00:00Z");

    @Test void firstFullScanKeepsAllAuthorizedHistoryAndExactDecimalsAcrossRestart() {
        try(var f = new EnergyHistoryFixture()) {
            String exact = "12345678901234567890.1234567890123456789";
            f.rows = List.of(EnergyHistoryFixture.row(METER,"ancient","2020-01-01T08:00:00+08:00",exact),
                    EnergyHistoryFixture.row(METER,"recent","2026-01-14T08:00:00+08:00","12345678901234567891.1234567890123456789"));
            f.register(f.identity());
            assertEquals(0,f.sourceCalls.get());
            f.directory.synchronizeDue();
            assertEquals("ENERGY_HISTORY_PREPARING",assertThrows(BusinessException.class,() -> window(f)).code());
            assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_requests",Integer.class));
            f.directory.synchronizeHistoryDue();
            var original = window(f);
            assertEquals(2,original.samples().size());
            assertEquals(0,original.samples().stream().filter(sample -> sample.sourceId().equals("ancient")).findFirst().orElseThrow().epf().compareTo(new BigDecimal(exact)));
            int calls = f.sourceCalls.get();
            f.unavailable=true;
            f.restart();
            assertEquals(original.samples(),window(f).samples());
            assertEquals(calls,f.sourceCalls.get());
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_samples",Integer.class));
            assertEquals("READY",f.jdbc.queryForObject("SELECT state FROM local_energy_requests",String.class));
        }
    }

    @Test void sameSourceIdentityIsUpsertedButSameTimeDifferentIdsAndFallbackNamespaceSurvive() {
        try(var f = new EnergyHistoryFixture()) {
            String at="2026-01-14T00:00:00Z";
            f.rows=List.of(EnergyHistoryFixture.row(METER,"a",at,"100"),EnergyHistoryFixture.row(METER,"b",at,"110"),
                    EnergyHistoryFixture.row(METER,null,at,"120"),EnergyHistoryFixture.row(METER,at,at,"130"));
            f.publish();
            assertEquals(4,window(f).samples().size());
            f.clock.advance(61);
            f.rows=List.of(EnergyHistoryFixture.row(METER,"a",at,"101"),EnergyHistoryFixture.row(METER,"b",at,"110"),
                    EnergyHistoryFixture.row(METER,null,at,"120"),EnergyHistoryFixture.row(METER,at,at,"130"));
            f.nextFull();
            assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_samples",Integer.class));
            assertEquals(0,window(f).samples().stream().filter(sample -> "a".equals(sample.sourceId())).findFirst().orElseThrow().epf().compareTo(new BigDecimal("101")));
            assertEquals(1,window(f).samples().stream().filter(EnergyHistory.Sample::sourceIdMissing).count());
        }
    }

    @Test void aFailedLaterPageDoesNotAdvanceCoverageOrOverwriteAnyPublishedSample() {
        try(var f = new EnergyHistoryFixture()) {
            f.rows=denseRows(501,"100");
            f.publish();
            var original=window(f);
            var success=f.jdbc.queryForObject("SELECT full_success_at FROM local_energy_sync",java.sql.Timestamp.class);
            f.rows=denseRows(501,"200");
            f.failPage=1;
            f.clock.advance(61);
            f.nextFull();
            assertEquals("FAILED",f.jdbc.queryForObject("SELECT full_state FROM local_energy_sync",String.class));
            assertEquals(success,f.jdbc.queryForObject("SELECT full_success_at FROM local_energy_sync",java.sql.Timestamp.class));
            assertEquals(original.samples(),window(f).samples());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_sample_batches",Integer.class));
            f.failPage=-1;
            f.clock.advance(61);
            f.directory.synchronizeHistoryDue();
            assertEquals("READY",f.jdbc.queryForObject("SELECT full_state FROM local_energy_sync",String.class));
            assertEquals(501,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_samples",Integer.class));
            assertEquals(0,window(f).samples().getFirst().epf().compareTo(new BigDecimal("200")));
        }
    }

    @Test void inconsistentSourcePaginationAndUnrepresentableDecimalsNeverCertifyAnEmptyHistory() {
        try(var f = new EnergyHistoryFixture()) {
            f.pageOverride=page -> "{\"code\":200,\"content\":{\"number\":0,\"last\":true,\"totalElements\":2,\"content\":[{\"id\":\"a\",\"equipmentId\":\""+METER+"\",\"readTime\":\"2020-01-01 08:00:00\",\"epf\":1}]}}";
            f.publish();
            assertEquals("FAILED",f.jdbc.queryForObject("SELECT full_state FROM local_energy_sync",String.class));
            assertEquals("ENERGY_HISTORY_PREPARING",assertThrows(BusinessException.class,() -> window(f)).code());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_coverage",Integer.class));
            f.pageOverride=null;
            f.rows=List.of(EnergyHistoryFixture.row(METER,"overflow","2020-01-01T00:00:00Z","1E+36"));
            f.clock.advance(61);f.nextFull();
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM local_energy_samples",Integer.class));
            assertNull(f.jdbc.queryForObject("SELECT full_success_at FROM local_energy_sync",java.sql.Timestamp.class));
        }
    }

    @Test void nightlyFullRunsAtShanghaiThreeAndCatchesUpOnceAfterDowntime() {
        try(var f = new EnergyHistoryFixture()) {
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));
            f.publish();
            assertEquals(Instant.parse("2026-01-15T19:00:00Z"),f.jdbc.queryForObject("SELECT full_next_due_at FROM local_energy_sync",java.sql.Timestamp.class).toInstant());
            int initial=f.sourceCalls.get();
            f.directory.synchronizeHistoryDue();
            assertEquals(initial,f.sourceCalls.get());
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","101"));
            f.clock.now=Instant.parse("2026-01-18T20:00:00Z");f.restart();
            f.directory.synchronizeHistoryDue();
            assertEquals(0,window(f).samples().getFirst().epf().compareTo(new BigDecimal("101")));
            int after=f.sourceCalls.get();
            f.directory.synchronizeHistoryDue();
            assertEquals(after,f.sourceCalls.get());
            assertEquals(Instant.parse("2026-01-19T19:00:00Z"),f.jdbc.queryForObject("SELECT full_next_due_at FROM local_energy_sync",java.sql.Timestamp.class).toInstant());
        }
    }

    @Test void dedicatedGrantsStaySeparateAndCredentialRenewalPreservesPhysicalHistoryTimes() {
        try(var f = new EnergyHistoryFixture()) {
            AuthorizedDevice other=new AuthorizedDevice("fixture-other-meter","energy","测试电表乙","测试位置乙",true);
            f.catalog=List.of(EnergyHistoryFixture.METER,other);
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));
            f.publish();
            assertTrue(f.directory.loadScope(f.identity()).device(other.id()).isPresent());
            assertTrue(f.history().authorizedScope(f.identity()).device(other.id()).isEmpty());
            assertEquals(403,assertThrows(BusinessException.class,() -> f.history().window(f.identity(),List.of(other.id()),FROM,TO,false)).httpStatus());
            var original = f.history().window(f.identity(),List.of(METER),FROM,TO,false);
            var rotated=f.identity("Bearer fixture-rotated","fixture-tenant");
            f.register(rotated);f.unavailable=true;f.directory.synchronizeDue();
            var retained = f.history().window(rotated,List.of(METER),FROM,TO,false);
            assertEquals(new BigDecimal("100").setScale(30), retained.samples().getFirst().epf());
            assertTrue(retained.missingCoverageMeterIds().isEmpty());
            assertEquals(original.cache().get("updatedAt"), retained.cache().get("updatedAt"));
            int calls = f.sourceCalls.get();
            f.unavailable=false;f.directory.synchronizeHistoryDue();
            assertEquals(calls, f.sourceCalls.get(), "同主体换证不应重新扫描已经完整保存的历史");
            f.restart();
            assertEquals(new BigDecimal("100").setScale(30),
                    f.history().window(rotated,List.of(METER),FROM,TO,false).samples().getFirst().epf());
        }
    }

    @Test void newMetersRemainCoverageGapsAndDatabaseFailureHasNoSourceFallback() {
        try(var f = new EnergyHistoryFixture()) {
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));f.publish();
            var other=new AuthorizedDevice("fixture-new-meter","energy","测试电表乙","测试位置乙",true);
            f.meters=List.of(EnergyHistoryFixture.METER,other);f.clock.advance(61);f.unavailable=true;f.directory.synchronizeDue();
            var view=f.history().window(f.identity(),List.of(METER,other.id()),FROM,TO,false);
            assertEquals(List.of(other.id()),view.missingCoverageMeterIds());
            assertEquals(1,view.samples().size());
            int calls=f.sourceCalls.get();f.jdbc.execute("SHUTDOWN");
            assertEquals(503,assertThrows(BusinessException.class,() -> window(f)).httpStatus());
            assertEquals(calls,f.sourceCalls.get());
        }
    }

    @Test void tenantOwnerAndExplicitGrantDenialCannotReadRetainedRows() {
        try(var f = new EnergyHistoryFixture()) {
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));f.publish();
            for(var identity:List.of(f.identity("Bearer fixture-other","fixture-tenant"),f.identity("Bearer fixture-energy","fixture-other-tenant"))) {
                assertThrows(BusinessException.class,() -> f.history().window(identity,List.of(METER),FROM,TO,false));
            }
            f.denied=new GatewayAuthorizationException(403);f.clock.advance(61);f.directory.synchronizeDue();
            assertEquals(403,assertThrows(BusinessException.class,() -> window(f)).httpStatus());
        }
    }

    @Test void anUncoveredWindowDoesNotEraseTheBackgroundRetryTime() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows=denseRows(501,"100");
            f.failPage=1;
            f.publish();
            int before=f.sourceCalls.get();
            assertEquals("ENERGY_HISTORY_PREPARING",assertThrows(BusinessException.class,() -> window(f)).code());
            f.directory.synchronizeHistoryDue();
            assertEquals(before,f.sourceCalls.get());
            f.failPage=-1;
            f.clock.advance(61);
            f.directory.synchronizeHistoryDue();
            assertTrue(window(f).missingCoverageMeterIds().isEmpty());
            assertEquals(0,window(f).samples().getFirst().epf().compareTo(new BigDecimal("100")));
        }
    }

    @Test void aFailedDedicatedGrantRefreshKeepsFactsButCannotClaimFreshHistory() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));
            f.publish();
            var published=window(f);
            assertEquals("fresh",published.cache().get("status"));
            f.clock.advance(1);
            f.grantUnavailable=true;
            f.nextFull();
            var retained=window(f);
            assertEquals("stale",retained.cache().get("status"));
            assertEquals(published.cache().get("updatedAt"),retained.cache().get("updatedAt"));
            assertEquals(published.samples(),retained.samples());
        }
    }

    @Test void aSlowFullScanCannotOverwriteANewerCurrentCollection() throws Exception {
        try (var f = new EnergyHistoryFixture()) {
            f.rows=List.of(EnergyHistoryFixture.row(METER,"original","2020-01-01T00:00:00Z","100"));
            f.publish();
            var entered=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            f.clock.advance(61);
            f.pageOverride=page -> {
                boolean historical=Thread.currentThread().getName().equals("fixture-full-scan");
                if (historical) {
                    entered.countDown();
                    try {
                        if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Fixture scan gate timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return "{\"code\":200,\"content\":{\"number\":0,\"last\":true,\"totalElements\":1,\"content\":[{\"id\":\"original\",\"equipmentId\":\""
                        +METER+"\",\"readTime\":\"2020-01-01 08:00:00\",\"epf\":"+(historical?"100":"200")+"}]}}";
            };
            var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker=new Thread(() -> { try { f.nextFull(); } catch(Throwable failure) { error.set(failure); } },"fixture-full-scan");
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
            assertNull(error.get());
            assertEquals(0,window(f).samples().getFirst().epf().compareTo(new BigDecimal("200")));
        }
    }

    @Test void unchangedGrantRefreshCannotInterruptCompleteLocalHistory() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            f.beforeSampleRead = () -> {
                f.clock.advance(65);
                f.synchronize();
            };
            var retained = window(f);
            assertTrue(retained.missingCoverageMeterIds().isEmpty());
            assertEquals(List.of(new BigDecimal("100").setScale(30)),
                    retained.samples().stream().map(EnergyHistory.Sample::epf).toList());
        }
    }

    @Test void grantPublicationCannotExposeAnArtificialEmptyMeterScope() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            var replacement = new AuthorizedDevice("fixture-replacement", "energy", "替换电表", "测试位置", true);
            f.beforeMeterRead = () -> {
                f.meters = List.of(replacement);
                f.clock.advance(65);
                f.synchronize();
            };
            assertEquals(List.of(replacement.id()),
                    f.history().authorizedScope(f.identity()).devices().stream().map(AuthorizedDevice::id).toList());
        }
    }

    @Test void oneWindowCannotMixSamplesFromBeforeAndAfterAnAtomicPublication() {
        try (var f = new EnergyHistoryFixture()) {
            var second = new AuthorizedDevice("fixture-meter-b", "energy", "测试电表乙", "测试位置乙", true);
            f.meters = List.of(EnergyHistoryFixture.METER, second);
            f.rows = List.of(EnergyHistoryFixture.row(METER, "first", "2026-01-14T08:00:00Z", "100"),
                    EnergyHistoryFixture.row(second.id(), "second", "2026-01-14T08:00:00Z", "200"));
            f.publish();
            f.afterSampleRead = () -> {
                f.rows = List.of(EnergyHistoryFixture.row(METER, "first", "2026-01-14T08:00:00Z", "110"),
                        EnergyHistoryFixture.row(second.id(), "second", "2026-01-14T08:00:00Z", "220"));
                f.clock.advance(65);
                f.synchronize();
            };
            var retained = f.history().window(f.identity(), List.of(METER, second.id()), FROM, TO, false);
            assertEquals(List.of(new BigDecimal("100").setScale(30), new BigDecimal("200").setScale(30)),
                    retained.samples().stream().map(EnergyHistory.Sample::epf).toList());
            var refreshed = f.history().window(f.identity(), List.of(METER, second.id()), FROM, TO, false);
            assertEquals(List.of(new BigDecimal("110").setScale(30), new BigDecimal("220").setScale(30)),
                    refreshed.samples().stream().map(EnergyHistory.Sample::epf).toList());
        }
    }

    @Test void revocationDuringAReadDiscardsAlreadyLoadedSamples() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            f.afterSampleRead = () -> {
                f.meters = List.of();
                f.clock.advance(65);
                f.synchronize();
            };
            var denied = assertThrows(BusinessException.class, () -> window(f));
            assertEquals(403, denied.httpStatus());
            assertEquals("DEVICE_OUTSIDE_SCOPE", denied.code());
        }
    }

    @Test void changedGrantIsAConflictRatherThanMissingHistoricalCoverage() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            f.afterSampleRead = () -> {
                f.meters = List.of(EnergyHistoryFixture.METER,
                        new AuthorizedDevice("fixture-added", "energy", "新增电表", "测试位置", true));
                f.clock.advance(65);
                f.synchronize();
            };
            var changed = assertThrows(BusinessException.class, () -> window(f));
            assertEquals(409, changed.httpStatus());
            assertEquals("ENERGY_AUTHORIZATION_CHANGED", changed.code());
            assertEquals(new BigDecimal("100").setScale(30), window(f).samples().getFirst().epf());
        }
    }

    @Test void batchedWindowPreservesAllBoundaryRecordsLatestSelectionAndRequestedMeters() {
        try (var f = new EnergyHistoryFixture()) {
            var other = new AuthorizedDevice("fixture-unselected", "energy", "另一电表", "测试位置", true);
            f.meters = List.of(EnergyHistoryFixture.METER, other);
            f.rows = List.of(
                    EnergyHistoryFixture.row(METER, "left-a", "2026-01-01T00:00:00Z", "10"),
                    EnergyHistoryFixture.row(METER, "left-b", "2026-01-01T00:00:00Z", "11"),
                    EnergyHistoryFixture.row(METER, "inside", "2026-01-02T00:00:00Z", "20"),
                    EnergyHistoryFixture.row(METER, "latest", "2026-01-14T00:00:00Z", "30"),
                    EnergyHistoryFixture.row(other.id(), "unselected", "2026-01-02T00:00:00Z", "999"));
            f.publish();
            var from = Instant.parse("2026-01-01T12:00:00Z");
            var to = Instant.parse("2026-01-02T12:00:00Z");
            var bounded = f.history().window(f.identity(), List.of(METER), from, to, false);
            assertEquals(java.util.Set.of("left-a", "left-b", "inside"),
                    bounded.samples().stream().map(EnergyHistory.Sample::sourceId).collect(java.util.stream.Collectors.toSet()));
            var latest = f.history().window(f.identity(), List.of(METER), from, to, true);
            assertEquals(java.util.Set.of("left-a", "left-b", "inside", "latest"),
                    latest.samples().stream().map(EnergyHistory.Sample::sourceId).collect(java.util.stream.Collectors.toSet()));
            assertTrue(latest.samples().stream().allMatch(sample -> METER.equals(sample.meterId())));
            assertTrue(latest.missingCoverageMeterIds().isEmpty());
        }
    }

    @Test void renewalMustVerifyItsOwnGrantAndCannotExposeRemovedMeters() {
        try (var f = new EnergyHistoryFixture()) {
            var removed = new AuthorizedDevice("fixture-removed", "energy", "撤权电表", "测试位置", true);
            f.meters = List.of(EnergyHistoryFixture.METER, removed);
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"),
                    EnergyHistoryFixture.row(removed.id(), "removed", "2020-01-01T00:00:00Z", "200"));
            f.publish();
            var renewed = f.identity("Bearer fixture-renewed", "fixture-tenant");
            f.register(renewed);
            f.grantUnavailable = true;
            f.directory.synchronizeDue();
            assertThrows(BusinessException.class, () -> f.history().window(renewed, List.of(METER), FROM, TO, false));
            f.grantUnavailable = false;
            f.unavailable = true;
            f.meters = List.of(EnergyHistoryFixture.METER);
            f.clock.advance(65);
            f.directory.synchronizeDue();
            assertEquals(new BigDecimal("100").setScale(30),
                    f.history().window(renewed, List.of(METER), FROM, TO, false).samples().getFirst().epf());
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> f.history().window(renewed, List.of(removed.id()), FROM, TO, false)).httpStatus());
            int calls = f.sourceCalls.get();
            f.directory.synchronizeHistoryDue();
            assertEquals(calls, f.sourceCalls.get(), "收窄授权不应重新采集剩余电表的已有完整历史");
        }
    }

    @Test void oldCredentialCollectionCannotOverwriteFactsAfterVerifiedRenewal() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2026-01-14T08:00:00Z", "100"));
            f.publish();
            var renewed = f.identity("Bearer fixture-renewed", "fixture-tenant");
            var rotated = new java.util.concurrent.atomic.AtomicBoolean();
            f.pageOverride = page -> {
                boolean oldCollection = rotated.compareAndSet(false, true);
                if (oldCollection) {
                    f.register(renewed);
                    f.clock.advance(65);
                    f.directory.synchronizeDue();
                }
                return "{\"code\":200,\"content\":{\"number\":0,\"last\":true,\"totalElements\":1,\"content\":[{\"id\":\"retained\",\"equipmentId\":\""
                        + METER + "\",\"readTime\":\"2026-01-14 16:00:00\",\"epf\":" + (oldCollection ? "999" : "200") + "}]}}";
            };
            f.nextFull();
            assertTrue(rotated.get());
            assertEquals(new BigDecimal("200").setScale(30),
                    f.history().window(renewed, List.of(METER), FROM, TO, false).samples().getFirst().epf());
        }
    }

    @Test void equivalentViewersShareFailedCurrentCollectionCooldownAndKeepPublishedHistory() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            var original = window(f);
            var newcomer = f.identity("Bearer fixture-other", "fixture-tenant");
            f.register(newcomer);
            f.directory.synchronizeDue();
            int calls = f.sourceCalls.get();
            f.clock.advance(61);
            f.unavailable = true;
            f.directory.synchronizeDue();
            assertEquals(calls + 1, f.sourceCalls.get(), "同一电表集合的采集失败不应由每个账号重复请求");
            var retained = f.history().window(newcomer, List.of(METER), FROM, TO, false);
            assertEquals(original.samples(), retained.samples());
            assertEquals("stale", retained.cache().get("status"));
        }
    }

    @Test void equivalentViewersShareInitialFullScanFailureAndResumeOnceAfterCooldown() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.unavailable = true;
            f.publish();
            assertEquals(1, f.sourceCalls.get());
            var newcomer = f.identity("Bearer fixture-other", "fixture-tenant");
            f.register(newcomer);
            f.synchronize();
            assertEquals(1, f.sourceCalls.get(), "同权限的新账号不得绕过既有全量失败的等待期");
            assertEquals("ENERGY_HISTORY_PREPARING", assertThrows(BusinessException.class,
                    () -> f.history().window(newcomer, List.of(METER), FROM, TO, false)).code());
            f.clock.advance(61);
            f.unavailable = false;
            f.directory.synchronizeHistoryDue();
            assertEquals(2, f.sourceCalls.get());
            assertEquals(new BigDecimal("100").setScale(30),
                    f.history().window(newcomer, List.of(METER), FROM, TO, false).samples().getFirst().epf());
        }
    }

    @Test void directoryRefreshDoesNotRepeatedlyDiscardAnInFlightCurrentObservation() {
        try (var f = new EnergyHistoryFixture()) {
            f.currentStates = new DeviceStateReadResult(Map.of(METER, Map.of("totalActivePower", BigDecimal.ONE)), f.clock.instant().toString());
            f.publish();
            f.clock.advance(61);
            String sampledAt = f.clock.instant().toString();
            f.currentStates = new DeviceStateReadResult(Map.of(METER, Map.of("totalActivePower", BigDecimal.TEN)), sampledAt);
            f.beforeStateRead = () -> {
                f.beforeStateRead = null;
                f.clock.advance(61);
                Thread metadata = new Thread(f.directory::synchronizeDue, "fixture-concurrent-directory-refresh");
                metadata.start();
                try { metadata.join(5000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(metadata.isAlive(), "metadata refresh should not wait for unrelated collection");
            };
            f.directory.synchronizeDue();
            var observations = DeviceObservations.jdbc(f.jdbc, f.transactions, f.mapper, f.directory, EnergyHistoryFixture.GATEWAY, f.clock);
            var result = observations.read(f.identity(), List.of(METER));
            assertEquals(0, BigDecimal.TEN.compareTo((BigDecimal) ((Map<?, ?>) result.states().get(METER)).get("totalActivePower")));
            assertEquals(sampledAt, result.observedAt());
        }
    }

    @Test void newOwnerCanReadSharedHistoryWhileAnExistingOwnersStateCollectionIsBlocked() throws Exception {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            var original = window(f);
            int calls = f.sourceCalls.get();
            f.clock.advance(61);
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            f.beforeStateRead = () -> {
                entered.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("fixture hold expired");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            };
            Thread worker = new Thread(() -> {
                try { f.directory.synchronizeDue(); }
                catch (Throwable error) { failure.set(error); }
            }, "fixture-existing-owner-collection");
            worker.start();
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var newcomer = f.identity("Bearer fixture-other", "fixture-tenant");
                f.register(newcomer);
                f.directory.synchronizeDue();
                assertEquals(List.of(EnergyHistoryFixture.METER), f.directory.loadBatch(newcomer).scope().devices());
                assertEquals(List.of(EnergyHistoryFixture.METER), f.history().authorizedScope(newcomer).devices());
                assertEquals(original.samples(), f.history().window(newcomer, List.of(METER), FROM, TO, false).samples());
                assertEquals(calls, f.sourceCalls.get(), "新账号授权准备不应等待或重新采集已有历史");
            } finally {
                f.beforeStateRead = null;
                release.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive(), "fixture worker did not drain");
            if (failure.get() != null) throw new AssertionError("fixture synchronization failed", failure.get());
        }
    }

    @Test void newlyAuthorizedOwnerReusesCompleteHistoryWithoutAnotherFullScan() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = denseRows(501, "100");
            f.publish();
            var original = window(f);
            int before = f.sourceCalls.get();
            var other = f.identity("Bearer fixture-other", "fixture-tenant");
            f.register(other);
            f.directory.synchronizeDue();
            int calls = f.sourceCalls.get();
            assertEquals(before, calls, "已有新鲜完整数据时新主体只核验授权，不重复采样");
            var reused = f.history().window(other, List.of(METER), FROM, TO, false);
            assertEquals(original.samples(), reused.samples());
            assertTrue(reused.missingCoverageMeterIds().isEmpty());
            f.directory.synchronizeHistoryDue();
            assertEquals(calls, f.sourceCalls.get(), "新主体不应重复全量扫描已有完整历史");
            f.restart();
            f.unavailable = true;
            assertEquals(original.samples(), f.history().window(other, List.of(METER), FROM, TO, false).samples());
        }
    }

    @Test void sharedHistoryStillRequiresTheViewersOwnGrantAndKeepsNewerSamples() {
        try (var f = new EnergyHistoryFixture()) {
            var hidden = new AuthorizedDevice("fixture-hidden", "energy", "受限电表", "测试位置", true);
            f.meters = List.of(EnergyHistoryFixture.METER, hidden);
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"),
                    EnergyHistoryFixture.row(hidden.id(), "hidden", "2020-01-01T00:00:00Z", "300"));
            f.publish();
            var other = f.identity("Bearer fixture-other", "fixture-tenant");
            f.meters = List.of(EnergyHistoryFixture.METER);
            f.clock.advance(65);
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "200"));
            f.register(other);
            f.directory.synchronizeDue();
            var reused = f.history().window(other, List.of(METER), FROM, TO, false);
            assertTrue(reused.missingCoverageMeterIds().isEmpty());
            assertEquals(List.of(new BigDecimal("200").setScale(30)),
                    reused.samples().stream().map(EnergyHistory.Sample::epf).toList());
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> f.history().window(other, List.of(hidden.id()), FROM, TO, false)).httpStatus());
            var tenant = f.identity("Bearer fixture-other", "fixture-other-tenant");
            f.register(tenant);
            f.unavailable = true;
            f.directory.synchronizeDue();
            assertEquals("ENERGY_HISTORY_PREPARING", assertThrows(BusinessException.class,
                    () -> f.history().window(tenant, List.of(METER), FROM, TO, false)).code());
        }
    }

    @Test void identicalMeterIdsFromAnotherGatewayCannotReuseHistory() {
        try (var f = new EnergyHistoryFixture()) {
            f.rows = List.of(EnergyHistoryFixture.row(METER, "retained", "2020-01-01T00:00:00Z", "100"));
            f.publish();
            f.gatewayBase = "https://other-energy-fixture.example.invalid";
            f.restart();
            f.register(f.identity());
            f.unavailable = true;
            f.directory.synchronizeDue();
            assertEquals("ENERGY_HISTORY_PREPARING", assertThrows(BusinessException.class, () -> window(f)).code());
        }
    }

    private static EnergyHistory.Window window(EnergyHistoryFixture f) { return f.history().window(f.identity(),List.of(METER),FROM,TO,false); }
    private static List<Map<String,Object>> denseRows(int count,String value) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int index=0;index<count;index++) rows.add(EnergyHistoryFixture.row(METER,"sample-"+index,
                Instant.parse("2026-01-14T12:00:00Z").minusSeconds(index*60L).toString(),value));
        return rows;
    }
}
