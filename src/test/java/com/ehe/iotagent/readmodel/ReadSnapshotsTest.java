package com.ehe.iotagent.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The production public seam is exercised with controlled time, scheduling and rebuildable persistence. */
class ReadSnapshotsTest {
    private static final ReadSnapshots.Identity COMPANY = identity("tenant", "Bearer secret-a", "COMPANY", "owner");
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-02T08:00:00Z"));
    private final Jobs upstream = new Jobs();
    private final Jobs storage = new Jobs();
    private final MemoryBarrier barrier = new MemoryBarrier(clock);

    @Test void coldPreparingHasNoEmptyBusinessDataAndConcurrentReadsCoalesce() {
        ReadSnapshots snapshots = snapshots(null);
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> load = () -> Map.of("devices", java.util.List.of(Map.of("id", calls.incrementAndGet())));
        Map<String, Object> cold = read(snapshots, load);
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("devices"));
        assertNull(cache(cold).get("updatedAt"));
        for (int i = 0; i < 20; i++) read(snapshots, load);
        assertEquals(1, upstream.size());
        assertEquals(0, calls.get());
        upstream.runNext();
        Map<String, Object> held = read(snapshots, load);
        assertEquals("fresh", cache(held).get("status"));
        assertEquals(1, calls.get());
        assertTrue(held.containsKey("devices"));
    }

    @Test void backgroundLoadRunsInlineWithoutQueueingASecondSnapshotTask() {
        ReadSnapshots snapshots = snapshots(null);
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> loader = () -> Map.of("readings", calls.incrementAndGet());
        Map<String, Object> loaded = snapshots.load(COMPANY, "history", Map.of("month", "2026-10"),
                ReadSnapshots.PolicyEnum.HISTORICAL, loader);
        assertEquals(1, loaded.get("readings"));
        assertEquals(1, calls.get());
        assertEquals(0, upstream.size());
        Map<String, Object> held = snapshots.load(COMPANY, "history", Map.of("month", "2026-10"),
                ReadSnapshots.PolicyEnum.HISTORICAL, loader);
        assertEquals(1, held.get("readings"));
        assertEquals(1, calls.get());
        assertEquals(0, upstream.size());
    }

    @Test void refreshActiveDoesNotDuplicateAnInlineBackgroundLoad() throws Exception {
        ReadSnapshots snapshots = snapshots(null);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread loader = new Thread(() -> snapshots.load(COMPANY, "history", Map.of("month", "2026-10"),
                ReadSnapshots.PolicyEnum.CURRENT, () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return Map.of("value", 1);
                }));
        loader.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        snapshots.refreshActive();
        assertEquals(0, upstream.size());
        release.countDown();
        loader.join(2000);
        assertEquals(1, calls.get());
        snapshots.refreshActive();
        assertEquals(0, upstream.size());
        assertEquals(1, calls.get());
    }

    @Test void heldReadReturnsWhileUpstreamIsSuspendedWithoutHoldingTheStateMonitor() throws Exception {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", 1));
        upstream.runNext();
        clock.advance(Duration.ofSeconds(61));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Map<String, Object>> blocked = () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            return Map.of("value", 2);
        };
        assertEquals(1, read(snapshots, blocked).get("value"));
        Thread worker = new Thread(upstream::runNext);
        worker.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            Map<String, Object> held = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> read(snapshots, blocked));
            assertEquals(1, held.get("value"));
            assertEquals("stale", cache(held).get("status"));
            assertEquals(true, cache(held).get("refreshing"));
        } finally {
            release.countDown();
            worker.join(2000);
        }
        assertEquals(2, read(snapshots, blocked).get("value"));
    }

    @Test void temporaryFailureKeepsHeldValueAndOriginalTimestampWithBoundedRetry() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", 7));
        upstream.runNext();
        String original = (String) cache(read(snapshots, () -> Map.of("value", 7))).get("updatedAt");
        clock.advance(Duration.ofSeconds(61));
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> failed = () -> { calls.incrementAndGet(); throw new BusinessException("GATEWAY_TIMEOUT", "上游暂不可用", 503); };
        read(snapshots, failed);
        upstream.runNext();
        Map<String, Object> held = read(snapshots, failed);
        assertEquals(7, held.get("value"));
        assertEquals(original, cache(held).get("updatedAt"));
        assertEquals("stale", cache(held).get("status"));
        assertEquals(false, cache(held).get("refreshing"));
        assertEquals("GATEWAY_TIMEOUT", ((Map<?, ?>) cache(held).get("lastError")).get("code"));
        for (int i = 0; i < 20; i++) read(snapshots, failed);
        snapshots.refreshActive();
        assertEquals(0, upstream.size());
        clock.advance(Duration.ofSeconds(60));
        snapshots.refreshActive();
        assertEquals(1, upstream.size());
        upstream.runNext();
        assertEquals(2, calls.get());
    }

    @Test void identitySourceRealmAndFullTypedQueryCannotShareAResult() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", "original"));
        upstream.runNext();
        for (ReadSnapshots.Identity identity : java.util.List.of(
                identity("other", COMPANY.authorization(), "COMPANY", "owner"),
                identity("tenant", "Bearer secret-b", "COMPANY", "owner"),
                identity("tenant", COMPANY.authorization(), "OFFICE", "owner"),
                identity("tenant", COMPANY.authorization(), "COMPANY", "other-owner"))) {
            assertEquals("preparing", snapshots.read(identity, "query", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", "other")).get("status"));
        }
        assertEquals("preparing", snapshots.read(COMPANY, "other-realm", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 1)).get("status"));
        assertEquals("preparing", snapshots.read(COMPANY, "query", Map.of("page", 1), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 1)).get("status"));
        assertEquals("preparing", snapshots.read(COMPANY, "query", Map.of("page", "1"), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 1)).get("status"));
        Map<String, Object> left = new LinkedHashMap<>(); left.put("a", 1); left.put("b", 2);
        Map<String, Object> right = new LinkedHashMap<>(); right.put("b", 2); right.put("a", 1);
        snapshots.read(COMPANY, "sorted", left, ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", "sorted"));
        int queued = upstream.size();
        snapshots.read(COMPANY, "sorted", right, ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", "sorted"));
        assertEquals(queued, upstream.size());
    }

    @Test void persistedUsableSnapshotRestoresAfterRestartWithoutCallingUpstream() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        read(first, () -> Map.of("value", 42, "authorization", "must-not-store", "nested", Map.of("accessToken", "secret")));
        assertEquals(0, upstream.size());
        storage.runNext();
        upstream.runNext();
        storage.runNext();
        assertEquals(1, persistence.values.size());
        String persisted = persistence.values.values().iterator().next().value;
        assertFalse(persisted.contains("secret"));
        assertFalse(persisted.contains(COMPANY.authorization()));
        assertTrue(persistence.values.keySet().stream().allMatch(key -> key.matches("readmodel:v2:[0-9a-f]{64}")));
        first.close();
        ReadSnapshots restarted = snapshots(persistence);
        AtomicInteger calls = new AtomicInteger();
        Supplier<Map<String, Object>> loader = () -> { calls.incrementAndGet(); return Map.of("value", 99); };
        assertEquals("preparing", read(restarted, loader).get("status"));
        storage.runNext();
        assertEquals(42, read(restarted, loader).get("value"));
        assertEquals(0, calls.get());
        assertEquals(0, upstream.size());
    }

    @Test void invalidationRetainsHeldValueButRejectsLateCompletionAndRefreshesItsGeneration() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", 1)); upstream.runNext();
        clock.advance(Duration.ofSeconds(61));
        read(snapshots, () -> { snapshots.invalidate(COMPANY); return Map.of("value", 2); });
        upstream.runNext();
        Map<String, Object> held = read(snapshots, () -> Map.of("value", 3));
        assertEquals(1, held.get("value"));
        assertEquals("stale", cache(held).get("status"));
        upstream.runNext();
        assertEquals(3, read(snapshots, () -> Map.of("value", 3)).get("value"));
        assertEquals("fresh", cache(read(snapshots, () -> Map.of("value", 3))).get("status"));
    }

    @Test void upstreamAuthorizationFailureClearsAllCredentialRealmsAndFailsClosed() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots snapshots = snapshots(persistence);
        read(snapshots, () -> Map.of("value", 1)); storage.runNext(); upstream.runNext(); storage.runNext();
        snapshots.read(COMPANY, "second", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2));
        storage.runNext(); upstream.runNext(); storage.runNext();
        clock.advance(Duration.ofSeconds(61));
        read(snapshots, () -> { throw new BusinessException("REVOKED", "登录失效", 401); });
        upstream.runNext();
        BusinessException denial = assertThrows(BusinessException.class, () -> read(snapshots, () -> Map.of("value", 3)));
        assertEquals(401, denial.httpStatus());
        assertThrows(BusinessException.class, () -> snapshots.read(COMPANY, "second", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 3)));
        storage.runAll();
        assertEquals(0, persistence.values.size());
        assertTrue(barrier.values.values().stream().anyMatch(state -> state.denialStatus() == 401));
    }

    @Test void lateSuccessfulLoaderCannotOverwriteExplicitRevocation() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> {
            snapshots.revoke(COMPANY, new BusinessException("DENIED", "禁止访问", 403));
            return Map.of("value", "late");
        });
        upstream.runNext();
        assertEquals(403, assertThrows(BusinessException.class, () -> read(snapshots, () -> Map.of("value", 1))).httpStatus());
    }

    @Test void invalidQueryPublishesRealTerminal400InsteadOfEndlessPreparing() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> { throw new BusinessException("INVALID_WINDOW", "窗口无效", 400); });
        upstream.runNext();
        BusinessException failure = assertThrows(BusinessException.class, () -> read(snapshots, () -> Map.of("value", 1)));
        assertEquals(400, failure.httpStatus());
        assertEquals("INVALID_WINDOW", failure.code());
        clock.advance(Duration.ofMinutes(2)); snapshots.refreshActive();
        assertEquals(0, upstream.size());
    }

    @Test void capacityIdleExpiryPersistenceRetentionAndShutdownAreBounded() {
        ReadSnapshots.Settings settings = new ReadSnapshots.Settings(1, Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(60));
        ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream, storage, null, null, new ObjectMapper(), settings);
        read(snapshots, () -> Map.of("value", 1)); upstream.runNext();
        Map<String, Object> full = snapshots.read(COMPANY, "other", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2));
        assertEquals("unavailable", full.get("status"));
        assertFalse(full.containsKey("value"));
        clock.advance(Duration.ofMinutes(31)); snapshots.refreshActive();
        assertEquals("preparing", snapshots.read(COMPANY, "other", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2)).get("status"));
        snapshots.close(); upstream.runNext();
        assertEquals("unavailable", read(snapshots, () -> Map.of("value", 3)).get("status"));

        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots persistent = snapshots(persistence);
        read(persistent, () -> Map.of("value", 4)); storage.runNext(); upstream.runNext(); storage.runNext(); persistent.close();
        clock.advance(Duration.ofDays(8));
        ReadSnapshots restored = snapshots(persistence);
        read(restored, () -> Map.of("value", 5)); storage.runNext();
        assertEquals("preparing", read(restored, () -> Map.of("value", 5)).get("status"));
        assertEquals(1, upstream.size());
    }

    @Test void historicalWindowsRefreshAtShanghaiNightAndMixedTodayWindowsStayCurrent() {
        assertEquals(ReadSnapshots.PolicyEnum.HISTORICAL, ReadSnapshots.PolicyEnum.forQuery(Map.of("from", "2026-10-01", "to", "2026-10-31"), clock));
        assertEquals(ReadSnapshots.PolicyEnum.CURRENT, ReadSnapshots.PolicyEnum.forQuery(Map.of("from", "2026-10-01", "to", "2026-11-02"), clock));
        assertEquals(ReadSnapshots.PolicyEnum.CURRENT, ReadSnapshots.PolicyEnum.forQuery(Map.of("to", "2026-10-31", "baselineTo", "2026-11-02"), clock));
        assertEquals(ReadSnapshots.PolicyEnum.HISTORICAL, ReadSnapshots.PolicyEnum.forQuery(Map.of("endMonth", "2026-10"), clock));
        assertEquals(ReadSnapshots.PolicyEnum.CURRENT, ReadSnapshots.PolicyEnum.forQuery(Map.of(), clock));
        ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream, storage, null, null, new ObjectMapper(),
                new ReadSnapshots.Settings(32, Duration.ofDays(2), Duration.ofDays(7), Duration.ofSeconds(60)));
        snapshots.read(COMPANY, "history", Map.of(), ReadSnapshots.PolicyEnum.HISTORICAL, () -> Map.of("value", 1)); upstream.runNext();
        clock.advance(Duration.ofHours(7)); snapshots.refreshActive(); assertEquals(0, upstream.size());
        clock.advance(Duration.ofHours(1)); snapshots.refreshActive(); assertEquals(1, upstream.size());
        assertEquals(Instant.parse("2026-11-02T16:00:00Z"), ReadSnapshots.PolicyEnum.HISTORICAL.nextRefresh(Instant.parse("2026-11-02T08:00:00Z")));
    }

    @Test void rejectedJobsPublishUnavailableAndDoNotBusyRetryOnEveryFrontendRead() {
        AtomicInteger submissions = new AtomicInteger();
        Executor rejected = job -> { submissions.incrementAndGet(); throw new java.util.concurrent.RejectedExecutionException(); };
        ReadSnapshots snapshots = ReadSnapshots.create(clock, rejected, storage, null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
        assertEquals("unavailable", read(snapshots, () -> Map.of("value", 1)).get("status"));
        for (int i = 0; i < 20; i++) read(snapshots, () -> Map.of("value", 1));
        assertEquals(1, submissions.get());
        clock.advance(Duration.ofSeconds(60));
        snapshots.refreshActive();
        assertEquals(2, submissions.get());
    }

    @Test void redisFailureFallsBackInBackgroundAndDoesNotRequireRedisToServeHeldMemory() {
        ReadSnapshots.Persistence broken = new ReadSnapshots.Persistence() {
            @Override public Optional<String> get(String key) { throw new IllegalStateException("redis disconnected"); }
            @Override public void put(String key, String value, Duration retention) { throw new IllegalStateException("redis disconnected"); }
            @Override public void delete(String key) { throw new IllegalStateException("redis disconnected"); }
        };
        ReadSnapshots snapshots = snapshots(broken);
        assertEquals("preparing", read(snapshots, () -> Map.of("value", 6)).get("status"));
        assertEquals(0, upstream.size());
        storage.runNext();
        upstream.runNext();
        // The queued failing Redis write has not run; held memory is already readable.
        assertEquals(6, read(snapshots, () -> Map.of("value", 6)).get("value"));
        storage.runNext();
        assertEquals(6, read(snapshots, () -> Map.of("value", 6)).get("value"));
    }

    @Test void durableGenerationInvalidatesUnloadedRedisQueriesAcrossProcessRestarts() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "known", 1);
        prepare(first, "unloaded", 2);
        first.close();
        ReadSnapshots second = snapshots(persistence);
        second.read(COMPANY, "known", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 9));
        storage.runNext();
        second.invalidate(COMPANY);
        storage.runAll();
        assertEquals(1, second.read(COMPANY, "known", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 9)).get("value"));
        // Its invalidated refresh is queued, but the process exits before running it.
        second.close(); upstream.runAll();
        ReadSnapshots third = snapshots(persistence);
        Supplier<Map<String, Object>> fresh = () -> Map.of("value", 99);
        third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, fresh);
        storage.runNext();
        Map<String, Object> cold = third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, fresh);
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("value"));
        upstream.runNext();
        assertEquals(99, third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, fresh).get("value"));
    }

    @Test void durableRevocationDeniesAnUnloadedQueryInANewProcess() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 7); first.close();
        ReadSnapshots second = snapshots(persistence);
        second.revoke(COMPANY, new BusinessException("DENIED", "无权读取", 403));
        storage.runAll(); second.close();
        ReadSnapshots third = snapshots(persistence);
        third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 8));
        storage.runNext();
        BusinessException denied = assertThrows(BusinessException.class, () ->
                third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 8)));
        assertEquals(403, denied.httpStatus());
        assertEquals(0, upstream.size());
    }

    @Test void discardClearsHeldAndPersistedGenerationsWithoutDenyingNewVerifiedReads() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 7);
        first.discard(COMPANY); storage.runAll(); first.close();
        ReadSnapshots second = snapshots(persistence);
        second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 8));
        storage.runNext();
        assertEquals(1, upstream.size());
        upstream.runNext();
        assertEquals(8, second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 8)).get("value"));
    }

    @Test void failedTombstoneCheckNeverRestoresAnOtherwiseUsableBody() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "query", 1); first.close();
        ReadSnapshots.Barrier failedCheck = new ReadSnapshots.Barrier() {
            @Override public ReadSnapshots.Barrier.Checkpoint startInstance(String id) { return barrier.startInstance(id); }
            @Override public boolean markClean(String id) { return barrier.markClean(id); }
            @Override public Optional<ReadSnapshots.Barrier.State> read(ReadSnapshots.Barrier.Key key) { throw new IllegalStateException("barrier unavailable"); }
            @Override public boolean write(ReadSnapshots.Barrier.Key key, ReadSnapshots.Barrier.State state) { throw new IllegalStateException("barrier unavailable"); }
            @Override public boolean writeIfCurrent(ReadSnapshots.Barrier.Key key, String expected, ReadSnapshots.Barrier.State state) { throw new IllegalStateException("barrier unavailable"); }
        };
        ReadSnapshots second = ReadSnapshots.create(clock, upstream, storage, persistence, failedCheck,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        read(second, () -> Map.of("value", 2)); storage.runNext();
        assertEquals("preparing", read(second, () -> Map.of("value", 2)).get("status"));
        upstream.runNext();
        assertEquals(2, read(second, () -> Map.of("value", 2)).get("value"));
    }

    @Test void suspendedRedisRestoreHasAWatchdogFallbackAndItsLateBodyCannotOverwriteFreshUpstream() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "query", 1); first.close();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ReadSnapshots.Persistence suspended = new ReadSnapshots.Persistence() {
            @Override public Optional<String> get(String key) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
                return persistence.get(key);
            }
            @Override public void put(String key, String value, Duration retention) { persistence.put(key, value, retention); }
            @Override public void delete(String key) { persistence.delete(key); }
        };
        ReadSnapshots second = snapshots(suspended);
        assertEquals("preparing", read(second, () -> Map.of("value", 2)).get("status"));
        Thread worker = new Thread(storage::runNext);
        worker.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            clock.advance(Duration.ofSeconds(3)); second.refreshActive();
            assertEquals(1, upstream.size());
            upstream.runNext();
            assertEquals(2, assertTimeoutPreemptively(Duration.ofSeconds(1), () -> read(second, () -> Map.of("value", 2))).get("value"));
        } finally {
            release.countDown(); worker.join(2000);
        }
        assertEquals(2, read(second, () -> Map.of("value", 2)).get("value"));
        assertEquals(0, upstream.size());
    }

    @Test void obsoleteAuthorizationFailureCannotRevokeTheReplacementGeneration() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", 1)); upstream.runNext();
        clock.advance(Duration.ofSeconds(61));
        read(snapshots, () -> {
            snapshots.invalidate(COMPANY);
            throw new BusinessException("OLD_DENIAL", "旧任务拒绝", 401);
        });
        upstream.runNext();
        assertEquals(1, read(snapshots, () -> Map.of("value", 3)).get("value"));
        upstream.runNext();
        assertEquals(3, read(snapshots, () -> Map.of("value", 3)).get("value"));
    }

    @Test void policyUpdatesWhenAnActiveQueryCrossesShanghaiMidnight() {
        ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream, storage, null, null, new ObjectMapper(),
                new ReadSnapshots.Settings(32, Duration.ofDays(2), Duration.ofDays(7), Duration.ofSeconds(60)));
        Map<String, Object> query = Map.of("to", "2026-11-02", "weeks", 4, "endDate", "2026-11-02");
        snapshots.read(COMPANY, "comparison", query, ReadSnapshots.PolicyEnum.forQuery(query, clock), () -> Map.of("value", 1));
        upstream.runNext();
        clock.advance(Duration.ofHours(8));
        assertEquals(ReadSnapshots.PolicyEnum.HISTORICAL, ReadSnapshots.PolicyEnum.forQuery(query, clock));
        snapshots.read(COMPANY, "comparison", query, ReadSnapshots.PolicyEnum.forQuery(query, clock), () -> Map.of("value", 2));
        upstream.runNext();
        clock.advance(Duration.ofMinutes(2)); snapshots.refreshActive();
        assertEquals(0, upstream.size());
        assertEquals(ReadSnapshots.PolicyEnum.CURRENT, ReadSnapshots.PolicyEnum.forQuery(Map.of("weeks", 4), clock));
    }

    @Test void restorationSaturationExpiresOnlyAfterTheInvalidatedBodiesRetentionWindow() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream, storage, persistence, barrier, new ObjectMapper(),
                new ReadSnapshots.Settings(1, Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofSeconds(60)));
        snapshots.discard(COMPANY);
        snapshots.discard(identity("tenant", "Bearer other", "COMPANY", "owner"));
        storage.runAll();
        clock.advance(Duration.ofSeconds(61)); snapshots.refreshActive();
        assertEquals("preparing", snapshots.read(identity("tenant", "Bearer third", "COMPANY", "owner"),
                "query", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 1)).get("status"));
        assertEquals(0, upstream.size());
        storage.runNext();
        assertEquals(1, upstream.size());
    }

    @Test void failedDurableGenerationWriteRetriesOnTheBoundedCadence() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 1); first.close();
        AtomicInteger attempts = new AtomicInteger();
        ReadSnapshots.Barrier transientFailure = new ReadSnapshots.Barrier() {
            @Override public ReadSnapshots.Barrier.Checkpoint startInstance(String id) { return barrier.startInstance(id); }
            @Override public boolean markClean(String id) { return barrier.markClean(id); }
            @Override public Optional<ReadSnapshots.Barrier.State> read(ReadSnapshots.Barrier.Key key) { return barrier.read(key); }
            @Override public boolean write(ReadSnapshots.Barrier.Key key, ReadSnapshots.Barrier.State state) {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary barrier write failure");
                return barrier.write(key, state);
            }
            @Override public boolean writeIfCurrent(ReadSnapshots.Barrier.Key key, String expected,
                    ReadSnapshots.Barrier.State state) {
                return barrier.writeIfCurrent(key, expected, state);
            }
        };
        ReadSnapshots second = ReadSnapshots.create(clock, upstream, storage, persistence, transientFailure,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        assertThrows(IllegalStateException.class, () -> second.invalidate(COMPANY));
        second.refreshActive(); assertEquals(0, storage.size());
        clock.advance(Duration.ofSeconds(60)); second.refreshActive(); storage.runNext();
        assertEquals(2, attempts.get());
        second.close();
        ReadSnapshots third = snapshots(persistence);
        third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2));
        storage.runNext();
        assertEquals("preparing", third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2)).get("status"));
        assertEquals(1, upstream.size());
    }

    @Test void loaderRejectionStaysPendingUntilBarrierWriteAndReadmissionNeverRestoresOldBody() throws Exception {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots warm = ReadSnapshots.create(clock, upstream, storage, persistence, barrier,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        prepare(warm, "query", 1);
        warm.close();

        GatedBarrier gate = new GatedBarrier(barrier);
        ReadSnapshots second = ReadSnapshots.create(clock, upstream, storage, ignoreDeletes(persistence), gate,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        second.read(COMPANY, "query", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 1));
        storage.runNext();
        assertEquals(1, read(second, () -> Map.of("value", 1)).get("value"));

        clock.advance(Duration.ofSeconds(61));
        read(second, () -> { throw new BusinessException("REVOKED", "登录失效", 401); });
        Thread worker = new Thread(upstream::runNext);
        worker.start();
        try {
            assertTrue(gate.awaitWrite());
            BusinessException pending = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> assertThrows(BusinessException.class, () -> read(second, () -> Map.of("value", 2))));
            assertEquals("SNAPSHOT_CREDENTIAL_PENDING", pending.code());
            assertEquals(503, pending.httpStatus());
            gate.fail = true;
        } finally {
            gate.release();
            try { worker.join(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        assertEquals("SNAPSHOT_CREDENTIAL_PENDING",
                assertThrows(BusinessException.class, () -> read(second, () -> Map.of("value", 2))).code());

        clock.advance(Duration.ofSeconds(60));
        second.refreshActive();
        storage.runAll();
        assertEquals("SNAPSHOT_CREDENTIAL_PENDING",
                assertThrows(BusinessException.class, () -> read(second, () -> Map.of("value", 2))).code());

        gate.fail = false;
        second.admit(COMPANY);
        Map<String, Object> cold = read(second, () -> Map.of("value", 2));
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("value"));
        upstream.runNext();
        assertEquals(2, read(second, () -> Map.of("value", 2)).get("value"));
    }

    @Test void cleanExitAllowsStoredRedisRestoreAfterGracefulRestart() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "query", 1);
        first.close();
        assertTrue(barrier.isClean());

        ReadSnapshots restarted = snapshots(persistence);
        assertEquals("preparing", read(restarted, () -> Map.of("value", 9)).get("status"));
        storage.runNext();
        assertEquals(1, read(restarted, () -> Map.of("value", 9)).get("value"));
        assertEquals(0, upstream.size());
    }

    @Test void uncleanExitSkipsStoredRedisAndRepreparesInTheNextProcess() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "query", 1);
        // Hard kill: no close, so the checkpoint remains dirty although the body is in Redis.

        ReadSnapshots restarted = snapshots(persistence);
        Map<String, Object> cold = read(restarted, () -> Map.of("value", 2));
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("value"));
        storage.runNext();
        assertEquals(1, upstream.size());
        upstream.runNext();
        assertEquals(2, read(restarted, () -> Map.of("value", 2)).get("value"));
    }

    @Test void uncleanProcessRestoresItsOwnCurrentEpochAfterMemoryExpiry() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        barrier.startUnclean();
        ReadSnapshots model = snapshots(persistence);
        assertFalse(barrier.isClean());
        prepare(model, "query", 7);
        String original = (String) cache(read(model, () -> Map.of("value", 7))).get("updatedAt");

        clock.advance(Duration.ofMinutes(31));
        model.refreshActive();
        AtomicInteger upstreamCalls = new AtomicInteger();
        Supplier<Map<String, Object>> hungGateway = () -> {
            upstreamCalls.incrementAndGet();
            throw new IllegalStateException("gateway hung");
        };
        Map<String, Object> cold = read(model, hungGateway);
        assertEquals("preparing", cold.get("status"));
        storage.runNext();
        Map<String, Object> held = read(model, hungGateway);
        assertEquals(7, held.get("value"));
        assertEquals(original, cache(held).get("updatedAt"));
        assertEquals(0, upstreamCalls.get());
    }

    @Test void pendingDenialOrUndrainedShutdownCannotMarkTheCheckpointClean() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots.Barrier failing = new ReadSnapshots.Barrier() {
            @Override public ReadSnapshots.Barrier.Checkpoint startInstance(String id) { return barrier.startInstance(id); }
            @Override public boolean markClean(String id) { return barrier.markClean(id); }
            @Override public Optional<ReadSnapshots.Barrier.State> read(ReadSnapshots.Barrier.Key key) { return barrier.read(key); }
            @Override public boolean write(ReadSnapshots.Barrier.Key key, ReadSnapshots.Barrier.State state) {
                throw new IllegalStateException("barrier unavailable");
            }
            @Override public boolean writeIfCurrent(ReadSnapshots.Barrier.Key key, String expected,
                    ReadSnapshots.Barrier.State state) {
                throw new IllegalStateException("barrier unavailable");
            }
        };
        ReadSnapshots model = ReadSnapshots.create(clock, upstream, storage, persistence, failing,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        assertThrows(IllegalStateException.class,
                () -> model.revoke(COMPANY, new BusinessException("DENIED", "无权读取", 403)));
        model.close();
        assertFalse(barrier.isClean());

        ReadSnapshots drained = snapshots(persistence);
        drained.close(false);
        assertFalse(barrier.isClean());
    }

    @Test void genuineReadmissionAfterRevocationClearsDenialAndNeverRestoresOldBody() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = ReadSnapshots.create(clock, upstream, storage, ignoreDeletes(persistence), barrier,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        prepare(first, "unloaded", 7);
        first.revoke(COMPANY, new BusinessException("DENIED", "无权读取", 403));
        storage.runAll();
        first.close();

        ReadSnapshots second = snapshots(persistence);
        second.admit(COMPANY);
        assertTrue(barrier.values.values().stream().allMatch(state -> state.denialStatus() == 0));
        Map<String, Object> cold = second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 8));
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("value"));
        assertEquals(1, upstream.size());
        upstream.runNext();
        assertEquals(8, second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 8)).get("value"));
    }

    @Test void redisWriteFailureDuringInvalidationCannotRestoreOldBodyAfterRestart() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 1);
        first.close();

        ReadSnapshots.Persistence redisDown = new ReadSnapshots.Persistence() {
            @Override public Optional<String> get(String key) { return persistence.get(key); }
            @Override public void put(String key, String value, Duration retention) { throw new IllegalStateException("redis read-only"); }
            @Override public void delete(String key) { throw new IllegalStateException("redis read-only"); }
        };
        ReadSnapshots second = ReadSnapshots.create(clock, upstream, storage, redisDown, barrier,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 9));
        storage.runNext();
        assertEquals(1, second.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 9)).get("value"));
        second.invalidate(COMPANY);
        storage.runAll();
        second.close();

        ReadSnapshots third = snapshots(persistence);
        Map<String, Object> cold = third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 2));
        assertEquals("preparing", cold.get("status"));
        assertFalse(cold.containsKey("value"));
        storage.runNext();
        assertEquals(1, upstream.size());
        upstream.runNext();
        assertEquals(2, third.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 2)).get("value"));
    }

    @Test void persistedMarkerLifetimeCoversSnapshotsWrittenAfterTheInvalidation() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 1);
        first.invalidate(COMPANY);
        storage.runAll();

        clock.advance(Duration.ofDays(6));
        first.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", 2));
        upstream.runNext();
        storage.runNext();
        clock.advance(Duration.ofDays(1).plusHours(1));
        first.close();

        ReadSnapshots restarted = snapshots(persistence);
        assertEquals("preparing", restarted.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 99)).get("status"));
        storage.runNext();
        assertEquals(2, restarted.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 99)).get("value"));
    }

    @Test void withoutADurableBarrierStoredRedisBodiesAreNeverRestored() {
        MemoryPersistence persistence = new MemoryPersistence(clock);
        ReadSnapshots first = snapshots(persistence);
        prepare(first, "unloaded", 1);
        first.close();

        ReadSnapshots memoryOnly = ReadSnapshots.create(clock, upstream, storage, persistence, null,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
        Map<String, Object> cold = memoryOnly.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 3));
        assertEquals("preparing", cold.get("status"));
        assertEquals(1, upstream.size());
        assertEquals(0, storage.size());
        upstream.runNext();
        assertEquals(3, memoryOnly.read(COMPANY, "unloaded", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("value", 3)).get("value"));
    }

    @Test void discardClearsAnEarlierInMemoryRevocationForTheSameCredential() {
        ReadSnapshots snapshots = snapshots(null);
        read(snapshots, () -> Map.of("value", 1)); upstream.runNext();
        snapshots.revoke(COMPANY, new BusinessException("DENIED", "无权读取", 403));
        assertThrows(BusinessException.class, () -> read(snapshots, () -> Map.of("value", 2)));
        snapshots.discard(COMPANY);
        assertEquals("preparing", read(snapshots, () -> Map.of("value", 2)).get("status"));
        upstream.runNext();
        assertEquals(2, read(snapshots, () -> Map.of("value", 2)).get("value"));
    }

    private static ReadSnapshots.Persistence ignoreDeletes(ReadSnapshots.Persistence delegate) {
        return new ReadSnapshots.Persistence() {
            @Override public Optional<String> get(String key) { return delegate.get(key); }
            @Override public void put(String key, String value, Duration retention) { delegate.put(key, value, retention); }
            @Override public void delete(String key) { /* Redis delete unavailable */ }
        };
    }

    private void prepare(ReadSnapshots snapshots, String realm, int value) {
        snapshots.read(COMPANY, realm, Map.of(), ReadSnapshots.PolicyEnum.CURRENT, () -> Map.of("value", value));
        storage.runNext(); upstream.runNext(); storage.runNext();
    }

    private ReadSnapshots snapshots(ReadSnapshots.Persistence persistence) {
        return ReadSnapshots.create(clock, upstream, storage, persistence, barrier, new ObjectMapper(),
                ReadSnapshots.Settings.defaults());
    }
    private static ReadSnapshots.Identity identity(String tenant, String token, String source, String owner) {
        return new ReadSnapshots.Identity(tenant, token, source, owner);
    }
    private static Map<String, Object> read(ReadSnapshots snapshots, Supplier<Map<String, Object>> loader) {
        return snapshots.read(COMPANY, "query", Map.of(), ReadSnapshots.PolicyEnum.CURRENT, loader);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cache(Map<String, Object> body) { return (Map<String, Object>) body.get("cache"); }
    private static final class GatedBarrier implements ReadSnapshots.Barrier {
        private final ReadSnapshots.Barrier delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        volatile boolean fail;
        GatedBarrier(ReadSnapshots.Barrier delegate) { this.delegate = delegate; }
        boolean awaitWrite() throws InterruptedException { return entered.await(1, TimeUnit.SECONDS); }
        void release() { released.countDown(); }
        @Override public ReadSnapshots.Barrier.Checkpoint startInstance(String id) { return delegate.startInstance(id); }
        @Override public boolean markClean(String id) { return delegate.markClean(id); }
        @Override public Optional<ReadSnapshots.Barrier.State> read(ReadSnapshots.Barrier.Key key) {
            return delegate.read(key);
        }
        @Override public boolean write(ReadSnapshots.Barrier.Key key, ReadSnapshots.Barrier.State state) {
            entered.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release missing");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            if (fail) throw new IllegalStateException("barrier unavailable");
            return delegate.write(key, state);
        }
        @Override public boolean writeIfCurrent(ReadSnapshots.Barrier.Key key, String expected,
                ReadSnapshots.Barrier.State state) {
            return delegate.writeIfCurrent(key, expected, state);
        }
    }

    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Clock clock;
        final Map<ReadSnapshots.Barrier.Key, ReadSnapshots.Barrier.State> values = new LinkedHashMap<>();
        private boolean clean = true;
        private String epoch = java.util.UUID.randomUUID().toString();
        private String instance;
        MemoryBarrier(Clock clock) { this.clock = clock; }
        boolean isClean() { synchronized (this) { return clean; } }
        void startUnclean() { synchronized (this) { clean = false; } }
        @Override public synchronized ReadSnapshots.Barrier.Checkpoint startInstance(String id) {
            boolean previous = clean;
            if (!previous) epoch = java.util.UUID.randomUUID().toString();
            instance = id;
            clean = false;
            return new ReadSnapshots.Barrier.Checkpoint(previous, epoch);
        }
        @Override public synchronized boolean markClean(String id) {
            if (!id.equals(instance)) return false;
            clean = true;
            return true;
        }
        @Override public Optional<ReadSnapshots.Barrier.State> read(ReadSnapshots.Barrier.Key key) {
            ReadSnapshots.Barrier.State state = values.get(key);
            return state == null || !state.until().isAfter(clock.instant()) ? Optional.empty() : Optional.of(state);
        }
        @Override public boolean write(ReadSnapshots.Barrier.Key key, ReadSnapshots.Barrier.State state) {
            ReadSnapshots.Barrier.State current = values.get(key);
            if (current != null && state.issuedAt().isBefore(current.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
        @Override public boolean writeIfCurrent(ReadSnapshots.Barrier.Key key, String expected, ReadSnapshots.Barrier.State state) {
            ReadSnapshots.Barrier.State current = values.get(key);
            if (expected == null) {
                if (current != null && current.until().isAfter(clock.instant())) return false;
            } else if (current == null || !expected.equals(current.generation())) {
                return false;
            }
            if (current != null && state.issuedAt().isBefore(current.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
    }
    private static final class Jobs implements Executor {
        private final Queue<Runnable> jobs = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable command) { jobs.add(command); }
        synchronized int size() { return jobs.size(); }
        void runAll() { while (size() > 0) runNext(); }
        void runNext() {
            Runnable job;
            synchronized (this) { job = jobs.remove(); }
            job.run();
        }
    }
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
    private static final class MemoryPersistence implements ReadSnapshots.Persistence {
        private final Clock clock;
        final Map<String, Stored> values = new LinkedHashMap<>();
        MemoryPersistence(Clock clock) { this.clock = clock; }
        @Override public Optional<String> get(String key) {
            Stored stored = values.get(key);
            return stored == null || !stored.until.isAfter(clock.instant()) ? Optional.empty() : Optional.of(stored.value);
        }
        @Override public void put(String key, String value, Duration retention) { values.put(key, new Stored(value, clock.instant().plus(retention))); }
        @Override public void delete(String key) { values.remove(key); }
        private record Stored(String value, Instant until) { }
    }
}
