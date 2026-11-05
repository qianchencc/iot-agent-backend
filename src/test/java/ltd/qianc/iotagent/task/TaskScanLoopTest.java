package ltd.qianc.iotagent.task;

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
