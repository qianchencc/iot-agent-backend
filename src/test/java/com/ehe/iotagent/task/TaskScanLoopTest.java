package com.ehe.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

class TaskScanLoopTest {

    @Test
    void cadenceFollowsTheLatestSavedValuePerCompletedCycleAndStopsCleanly() {
        RecordingScheduler phase = new RecordingScheduler();
        RecordingScheduler background = new RecordingScheduler();
        TaskScanner scanner = mock(TaskScanner.class);
        int[] savedInterval = {1000};

        TaskScanLoop loop = new TaskScanLoop(scanner, () -> savedInterval[0], phase, background);
        loop.recoverOnStartup();

        phase.runNext();
        verify(scanner, times(1)).scanOnce();
        assertThat(phase.lastDelay).isEqualTo(Duration.ofMillis(1000));

        savedInterval[0] = 5000;
        phase.runNext();
        verify(scanner, times(2)).scanOnce();
        assertThat(phase.lastDelay).isEqualTo(Duration.ofMillis(5000));

        background.runNext();
        verify(scanner, times(1)).recoverReports();
        assertThat(background.lastDelay).isEqualTo(Duration.ofMillis(5000));

        loop.stop();
        verify(scanner).stopAndDrain();
        phase.runNext();
        verify(scanner, times(2)).scanOnce();
        assertThat(phase.pending).isEmpty();
    }

    @Test
    void aFailingCycleKeepsTheLoopAliveAndSchedulesTheNextOne() {
        RecordingScheduler phase = new RecordingScheduler();
        RecordingScheduler background = new RecordingScheduler();
        TaskScanner scanner = mock(TaskScanner.class);
        doThrow(new IllegalStateException("store hiccup")).doReturn(new TaskScanner.ScanResult(0, 0, 0, 0))
                .when(scanner).scanOnce();

        TaskScanLoop loop = new TaskScanLoop(scanner, () -> 2000, phase, background);
        loop.recoverOnStartup();
        phase.runNext();
        phase.runNext();
        verify(scanner, times(2)).scanOnce();
        assertThat(phase.lastDelay).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void closingSpringContextStopsResubmissionBeforeSchedulersRejectWork() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var errors = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
        TaskScanner scanner = mock(TaskScanner.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("shutdown did not release scan");
            return new TaskScanner.ScanResult(0, 0, 0, 0);
        }).when(scanner).scanOnce();
        var phase = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler() {
            @Override
            public void onApplicationEvent(org.springframework.context.event.ContextClosedEvent event) {
                super.onApplicationEvent(event);
                release.countDown();
            }
        };
        var background = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        phase.setErrorHandler(errors::add);
        background.setErrorHandler(errors::add);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("phase", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class, () -> phase);
            context.registerBean("background", org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler.class, () -> background);
            context.registerBean(TaskScanLoop.class, () -> new TaskScanLoop(scanner, () -> 1, phase, background));
            context.refresh();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
        assertThat(errors).isEmpty();
        verify(scanner).stopAndDrain();
    }

    private static final class RecordingScheduler extends ConcurrentTaskScheduler {
        private final List<Runnable> pending = new ArrayList<>();
        private Duration lastDelay;
        @Override
        public java.time.Clock getClock() {
            return java.time.Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), java.time.ZoneOffset.UTC);
        }

        void runNext() {
            pending.remove(0).run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant start) {
            lastDelay = Duration.between(getClock().instant(), start);
            pending.add(task);
            return new CancelableFuture(task, pending);
        }
    }

    private static final class CancelableFuture implements ScheduledFuture<Object> {
        private final Runnable task;
        private final List<Runnable> pending;

        CancelableFuture(Runnable task, List<Runnable> pending) {
            this.task = task;
            this.pending = pending;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return pending.remove(task);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }
    }
}
