package com.ehe.iotagent.task;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Behavioral regressions for the migrated Runtime scanner concurrency:
 * independent due tasks execute through a rolling bounded window (peak six,
 * every completion immediately frees its slot for the next due task instead
 * of waiting for the slowest peer), phases of one task keep their due order
 * and never overlap, a claimed phase promotes its parent to EXECUTING for
 * in-flight observers, interval starts keep advancing while another task
 * blocks the window, orphaned all-terminal parents finalize on later scans,
 * one task's failure never stops the other tasks in the window, and
 * stopAndDrain waits for the claimed in-flight phases instead of abandoning
 * them.
 */
class TaskScannerConcurrencyContractTest {
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");

    @Test
    void independentTasksPeakAtSixAndTheSeventhPhaseIsNotClaimedEarly() throws Exception {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        for (int index = 1; index <= 7; index++) {
            String taskId = "task-" + index;
            phases.add(phase(taskId, "phase-" + index));
            tasks.put(task(taskId));
        }
        BatchingGateway gateway = new BatchingGateway();
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FutureTask<TaskScanner.ScanResult> scan = new FutureTask<>(scanner::scanOnce);
        Thread.ofVirtual().name("scan-once").start(scan);
        assertThat(gateway.sixArrived.await(5, TimeUnit.SECONDS))
                .as("前6个独立任务阶段应并发执行").isTrue();
        assertThat(gateway.started.get())
                .as("在途峰值为6时不得领取第7个阶段").isEqualTo(6);
        gateway.release.countDown();
        TaskScanner.ScanResult result = scan.get(10, TimeUnit.SECONDS);

        assertThat(result.attempted()).isEqualTo(7);
        assertThat(result.claimed()).isEqualTo(7);
        assertThat(result.completed()).isEqualTo(7);
        assertThat(result.interrupted()).isZero();
        assertThat(gateway.peak.get()).as("在途峰值应为6").isEqualTo(6);
        Integer firstEnd = firstEvent(gateway.events, "end:");
        Integer seventhStart = gateway.events.indexOf("start:phase-7");
        assertThat(seventhStart).as("第7阶段必须被执行").isGreaterThan(-1);
        assertThat(firstEnd).isNotNull();
        assertThat(seventhStart).as("第7阶段只能在前6个之一完成后被领取")
                .isGreaterThan(firstEnd);
        for (int index = 1; index <= 7; index++) {
            assertThat(phases.status("phase-" + index)).isEqualTo("COMPLETED");
        }
    }

    @Test
    void phasesOfOneTaskKeepDueOrderAndNeverOverlap() {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-1"));
        for (int index = 1; index <= 3; index++) {
            phases.add(phase("task-1", "phase-1-" + index));
        }
        OrderingGateway gateway = new OrderingGateway();
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        TaskScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.claimed()).isEqualTo(3);
        assertThat(result.completed()).isEqualTo(3);
        assertThat(gateway.executedPhases).containsExactly("phase-1-1", "phase-1-2", "phase-1-3");
        assertThat(gateway.taskOverlap.get()).as("同一任务的阶段不得重叠").isFalse();
        assertThat(tasks.find("task-1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void oneTaskFailureDoesNotStopTheOtherTasksInTheWindow() {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        MemoryNotifications notifications = new MemoryNotifications();
        phases.add(phase("task-failing", "phase-failing"));
        phases.add(phase("task-healthy", "phase-healthy"));
        tasks.put(task("task-failing"));
        tasks.put(task("task-healthy"));
        TaskExecutionGateway gateway = request -> {
            if ("phase-failing".equals(request.phaseId())) {
                throw new IllegalStateException("上游控制请求中断");
            }
            return new TaskExecutionGateway.TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        };
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(), null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.attempted()).isEqualTo(2);
        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.interrupted()).isEqualTo(1);
        assertThat(phases.status("phase-failing")).isEqualTo("INTERRUPTED");
        assertThat(phases.status("phase-healthy")).isEqualTo("COMPLETED");
        assertThat(notifications.byTask.keySet())
                .as("两个任务的终态通知都必须落盘").containsExactlyInAnyOrder("task-failing", "task-healthy");
    }

    @Test
    void stopAndDrainWaitsForInFlightPhasesAndSkipsLaterClaims() throws Exception {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-drain"));
        phases.add(phase("task-drain", "phase-blocked"));
        phases.add(phase("task-drain", "phase-after-stop"));
        BlockingGateway gateway = new BlockingGateway();
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FutureTask<TaskScanner.ScanResult> scan = new FutureTask<>(scanner::scanOnce);
        Thread.ofVirtual().name("scan-once").start(scan);
        assertThat(gateway.started.await(5, TimeUnit.SECONDS)).isTrue();

        FutureTask<Void> drain = new FutureTask<>(() -> {
            scanner.stopAndDrain();
            return null;
        });
        Thread.ofVirtual().name("stop-and-drain").start(drain);
        try {
            assertThat(gateway.interrupted.await(5, TimeUnit.SECONDS))
                    .as("停机信号必须到达当前批次，阻止后续设备下发").isTrue();
            assertThat(drain.isDone()).as("在途阶段未收口前stopAndDrain不得返回").isFalse();
        } finally {
            gateway.release.countDown();
            drain.get(5, TimeUnit.SECONDS);
            scan.get(5, TimeUnit.SECONDS);
        }
        assertThat(phases.status("phase-blocked")).isEqualTo("INTERRUPTED");
        assertThat(phases.status("phase-after-stop"))
                .as("停止后不得再领取同任务后续阶段").isEqualTo("PENDING");
    }

    @Test
    void interruptedScanDrainsItsWindowWithoutClaimingTheNextTask() throws Exception {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        for (int index = 1; index <= 7; index++) {
            tasks.put(task("task-" + index));
            phases.add(phase("task-" + index, "phase-" + index));
        }
        BlockingGateway blocked = new BlockingGateway();
        CountDownLatch sixStarted = new CountDownLatch(6);
        AtomicInteger calls = new AtomicInteger();
        TaskExecutionGateway gateway = request -> {
            calls.incrementAndGet();
            sixStarted.countDown();
            return blocked.execute(request);
        };
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.concurrent.atomic.AtomicBoolean interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
        FutureTask<TaskScanner.ScanResult> scan = new FutureTask<>(() -> {
            try { return scanner.scanOnce(); }
            finally { interruptRestored.set(Thread.currentThread().isInterrupted()); }
        });
        Thread caller = Thread.ofVirtual().start(scan);
        try {
            assertThat(sixStarted.await(5, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            assertThat(blocked.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(scan.isDone()).as("interrupted scan must wait for in-flight execution").isFalse();
        } finally {
            blocked.release.countDown();
        }
        try {
            scan.get(5, TimeUnit.SECONDS);
            fail("interrupted scan must retain its interruption outcome");
        } catch (java.util.concurrent.ExecutionException error) {
            assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
        }
        assertThat(interruptRestored.get()).isTrue();
        assertThat(calls.get()).isEqualTo(6);
        assertThat(phases.status("phase-7")).isEqualTo("PENDING");
        for (int index = 1; index <= 6; index++) {
            assertThat(phases.status("phase-" + index)).isEqualTo("INTERRUPTED");
        }
    }

    @Test
    void claimedPhasePromotesParentToExecutingForInFlightObservers() {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-live"));
        phases.add(phase("task-live", "phase-live"));
        AtomicBoolean parentExecutingWhenObserved = new AtomicBoolean();
        TaskExecutionGateway gateway = request -> {
            parentExecutingWhenObserved.set(
                    "EXECUTING".equals(tasks.find(request.taskId()).orElseThrow().status()));
            return new TaskExecutionGateway.TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        };
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskScanner.ScanResult result = scanner.scanOnce();

        assertThat(parentExecutingWhenObserved.get())
                .as("阶段在途时父任务必须已是 EXECUTING 而非 SCHEDULED").isTrue();
        assertThat(result.completed()).isEqualTo(1);
        assertThat(tasks.find("task-live").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void freedSlotRunsTheNextTaskBeforeTheSlowPeerReleases() throws Exception {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        for (int index = 1; index <= 7; index++) {
            tasks.put(task("task-" + index));
            phases.add(phase("task-" + index, "phase-" + index));
        }
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch seventhCompleted = new CountDownLatch(1);
        TaskExecutionGateway gateway = request -> {
            if ("phase-1".equals(request.phaseId())) {
                slowStarted.countDown();
                try {
                    if (!slowRelease.await(10, TimeUnit.SECONDS)) fail("慢任务未被释放");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    fail("测试被中断");
                }
                return new TaskExecutionGateway.TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
            }
            if ("phase-7".equals(request.phaseId())) seventhCompleted.countDown();
            return new TaskExecutionGateway.TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        };
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FutureTask<TaskScanner.ScanResult> scan = new FutureTask<>(scanner::scanOnce);
        Thread.ofVirtual().name("scan-once").start(scan);

        assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seventhCompleted.await(5, TimeUnit.SECONDS))
                .as("慢任务占用窗口时，空出的槽位必须立刻执行后续任务").isTrue();
        assertThat(scan.isDone()).as("慢任务未完成前扫描不得收口").isFalse();

        slowRelease.countDown();
        TaskScanner.ScanResult result = scan.get(10, TimeUnit.SECONDS);

        assertThat(result.claimed()).isEqualTo(7);
        assertThat(result.completed()).isEqualTo(7);
        assertThat(phases.status("phase-1")).isEqualTo("COMPLETED");
    }

    @Test
    void intervalStartPromotesWhileAnotherTaskBlocksTheWindow() throws Exception {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-blocker"));
        phases.add(phase("task-blocker", "phase-blocker"));
        tasks.put(new TaskFact("task-interval", 1, "SCHEDULED",
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\","
                        + "\"interval\":{\"startsAt\":\"" + NOW.minusSeconds(60) + "\","
                        + "\"endsAt\":\"" + NOW.plusSeconds(3600) + "\"}}",
                "task-interval"));
        phases.startedIntervalTasks.add("task-interval");
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch blockerRelease = new CountDownLatch(1);
        TaskExecutionGateway gateway = request -> {
            blockerStarted.countDown();
            try {
                if (!blockerRelease.await(10, TimeUnit.SECONDS)) fail("阻塞任务未被释放");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                fail("测试被中断");
            }
            return new TaskExecutionGateway.TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        };
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway,
                new MemoryNotifications(), (checkpoint, identity) -> Optional.empty(),
                null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
        FutureTask<TaskScanner.ScanResult> scan = new FutureTask<>(scanner::scanOnce);
        Thread.ofVirtual().name("scan-once").start(scan);
        try {
            assertThat(blockerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // The window opened before the interval task became observable, so
            // only the bounded in-scan sweep may observe the transition.
            phases.exposeStartedIntervals.set(true);
            boolean promoted = false;
            for (int attempt = 0; attempt < 100 && !promoted; attempt++) {
                promoted = "EXECUTING".equals(tasks.find("task-interval").orElseThrow().status());
                if (!promoted) Thread.sleep(50);
            }
            assertThat(promoted).as("慢执行阻塞窗口时区间开始必须推进").isTrue();
            assertThat(scan.isDone()).as("阻塞任务未完成前扫描不得收口").isFalse();
        } finally {
            blockerRelease.countDown();
            scan.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void orphanedTerminalParentIsFinalizedWithNotificationOnNextScan() {
        RecordingPhases phases = new RecordingPhases();
        MemoryTasks tasks = new MemoryTasks();
        MemoryNotifications notifications = new MemoryNotifications();
        phases.add(phase("task-orphan", "phase-orphan"));
        // Simulate an interruption after the last phase committed but before
        // the parent terminal transaction: phases terminal, parent active.
        phases.claim("phase-orphan", 1, NOW);
        phases.finish("phase-orphan", 2, "COMPLETED", "{\"outcome\":\"COMPLETED\"}", NOW);
        tasks.put(task("task-orphan"));
        TaskExecutionGateway gateway = request -> fail("孤儿收口不得重新下发阶段");
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(), null, null, null, null, null, null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskScanner.ScanResult result = scanner.scanOnce();

        assertThat(result.claimed()).isZero();
        assertThat(tasks.find("task-orphan").orElseThrow().status())
                .as("全终态普通任务在下一次扫描必须收口").isEqualTo("COMPLETED");
        assertThat(notifications.byTask.keySet()).containsExactly("task-orphan");
        assertThat(notifications.byTask.get("task-orphan").outcome()).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ fakes

    /** Blocks its single execution until released, for drain assertions. */
    private static final class BlockingGateway implements TaskExecutionGateway {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            started.countDown();
            boolean stopped = false;
            try {
                while (true) {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) fail("在途阶段未被释放");
                        break;
                    } catch (InterruptedException error) {
                        stopped = true;
                        interrupted.countDown();
                    }
                }
            } finally {
                if (stopped) Thread.currentThread().interrupt();
            }
            if (stopped) throw new TaskExecutionGateway.DispatchException("在途结果未知", true);
            return new TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        }
    }

    private static Integer firstEvent(List<String> events, String prefix) {
        synchronized (events) {
            for (int index = 0; index < events.size(); index++) {
                if (events.get(index).startsWith(prefix)) return index;
            }
        }
        return null;
    }

    private static TaskPhaseStore.TaskPhase phase(String taskId, String phaseId) {
        return new TaskPhaseStore.TaskPhase(taskId, phaseId, 1, "company", "{\"read\":true}", NOW, "PENDING");
    }

    private static TaskFact task(String taskId) {
        return new TaskFact(taskId, 1, "SCHEDULED",
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"owner-1\",\"objective\":\"并发回归\"}",
                taskId);
    }

    /** Blocks the first six executions until released, recording start/end events. */
    private static final class BatchingGateway implements TaskExecutionGateway {
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch sixArrived = new CountDownLatch(6);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            int ordinal = started.incrementAndGet();
            events.add("start:" + request.phaseId());
            int current = inFlight.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            if (ordinal <= 6) {
                sixArrived.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) fail("在途阶段未被释放");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    fail("测试被中断");
                }
            }
            events.add("end:" + request.phaseId());
            inFlight.decrementAndGet();
            return new TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        }
    }


    /** Records per-task overlap for single-task ordering assertions. */
    private static final class OrderingGateway implements TaskExecutionGateway {
        final List<String> executedPhases = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, AtomicInteger> activeByTask = new LinkedHashMap<>();
        final AtomicBoolean taskOverlap = new AtomicBoolean();

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            executedPhases.add(request.phaseId());
            AtomicInteger active;
            synchronized (activeByTask) {
                active = activeByTask.computeIfAbsent(request.taskId(), ignored -> new AtomicInteger());
            }
            if (active.incrementAndGet() > 1) taskOverlap.set(true);
            active.decrementAndGet();
            return new TaskExecutionResult("COMPLETED", "{\"outcome\":\"COMPLETED\"}", true);
        }
    }

    private static final class RecordingPhases implements TaskPhaseStore {
        private final Map<String, TaskPhase> phases = new LinkedHashMap<>();
        /** Test seam: started interval tasks stay hidden until the test unlocks them mid-scan. */
        final AtomicBoolean exposeStartedIntervals = new AtomicBoolean();
        final Set<String> startedIntervalTasks = ConcurrentHashMap.newKeySet();

        void add(TaskPhase phase) {
            synchronized (this) {
                phases.put(phase.phaseId(), phase);
            }
        }

        @Override public synchronized List<TaskPhase> due(Instant now, int limit) {
            return phases.values().stream()
                    .filter(phase -> "PENDING".equals(phase.status()) && !phase.dueAt().isAfter(now))
                    .limit(limit).toList();
        }

        @Override public synchronized boolean claim(String phaseId, long expectedVersion, Instant claimedAt) {
            TaskPhase phase = phases.get(phaseId);
            if (phase == null || !"PENDING".equals(phase.status()) || phase.version() != expectedVersion) return false;
            phases.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "EXECUTING"));
            return true;
        }

        @Override public synchronized Optional<TaskPhase> find(String phaseId) {
            return Optional.ofNullable(phases.get(phaseId));
        }

        @Override public synchronized boolean finish(String phaseId, long expectedVersion, String status,
                                                      String payloadJson, Instant endedAt) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("终态写入被中断");
            TaskPhase phase = phases.get(phaseId);
            if (phase == null || !"EXECUTING".equals(phase.status()) || phase.version() != expectedVersion) return false;
            phases.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), payloadJson, phase.dueAt(), status));
            return true;
        }

        @Override public int recoverExecuting(Instant at) { return 0; }

        @Override public synchronized List<String> startedIntervals(Instant now, int limit) {
            if (!exposeStartedIntervals.get()) return List.of();
            return startedIntervalTasks.stream().limit(limit).toList();
        }

        /** Mirrors the store contract: active parents whose phases are all terminal. */
        @Override public synchronized List<String> readyToFinalize(Instant now, int limit) {
            Map<String, List<TaskPhase>> byTask = new LinkedHashMap<>();
            for (TaskPhase phase : phases.values()) {
                byTask.computeIfAbsent(phase.taskId(), ignored -> new ArrayList<>()).add(phase);
            }
            return byTask.entrySet().stream()
                    .filter(entry -> !entry.getValue().isEmpty())
                    .filter(entry -> entry.getValue().stream().noneMatch(phase ->
                            "PENDING".equals(phase.status()) || "EXECUTING".equals(phase.status())))
                    .map(Map.Entry::getKey)
                    .limit(limit)
                    .toList();
        }

        @Override public synchronized boolean allTerminal(String taskId) {
            return phases.values().stream().filter(phase -> phase.taskId().equals(taskId))
                    .allMatch(phase -> !"PENDING".equals(phase.status()) && !"EXECUTING".equals(phase.status()));
        }

        @Override public synchronized List<String> outcomes(String taskId) {
            List<String> values = new ArrayList<>();
            for (TaskPhase phase : phases.values()) {
                if (phase.taskId().equals(taskId)) values.add(phase.status());
            }
            return values;
        }

        synchronized String status(String phaseId) {
            TaskPhase phase = phases.get(phaseId);
            return phase == null ? null : phase.status();
        }
    }

    private static final class MemoryTasks implements TaskFactStore {
        private final Map<String, TaskFact> values = new LinkedHashMap<>();

        void put(TaskFact task) {
            synchronized (this) {
                values.put(task.taskId(), task);
            }
        }

        @Override public synchronized TaskFact save(TaskFact task) { values.put(task.taskId(), task); return task; }
        @Override public synchronized Optional<TaskFact> find(String taskId) { return Optional.ofNullable(values.get(taskId)); }
        @Override public synchronized Optional<TaskFact> findByIdempotencyKey(String key) { return Optional.empty(); }
        @Override public synchronized Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
            TaskFact current = values.get(replacement.taskId());
            if (current == null || current.version() != expectedVersion) return Optional.empty();
            values.put(replacement.taskId(), replacement);
            return Optional.of(replacement);
        }
    }

    private static final class MemoryNotifications implements TaskNotificationStore {
        private final Map<String, TaskNotificationFact> byTask = new LinkedHashMap<>();

        @Override public synchronized Optional<TaskNotificationFact> find(String id) {
            return byTask.values().stream().filter(item -> item.notificationId().equals(id)).findFirst();
        }

        @Override public synchronized TaskNotificationFact record(TaskNotificationFact notification) {
            byTask.putIfAbsent(notification.taskId(), notification);
            return byTask.get(notification.taskId());
        }

        @Override public synchronized List<TaskNotificationFact> list(String tenantCode, String recipientKey,
                                                                      String source, int limit, String before) {
            return List.copyOf(byTask.values());
        }
    }
}
