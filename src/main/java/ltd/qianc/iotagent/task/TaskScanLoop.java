package ltd.qianc.iotagent.task;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.scheduling.TaskScheduler;

/**
 * Polls the MySQL phase queue and recovers in-flight facts on boot. The delay
 * supplier is read after every completed cycle, so a saved admin value takes
 * effect on the next cycle while the current cycle finishes undisturbed.
 */
public final class TaskScanLoop {
    private final TaskScanner scanner;
    private final LongSupplier scanIntervalMs;
    private final TaskScheduler phaseScheduler;
    private final TaskScheduler backgroundScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TaskScanLoop(TaskScanner scanner, LongSupplier scanIntervalMs,
            TaskScheduler phaseScheduler, TaskScheduler backgroundScheduler) {
        this.scanner = scanner;
        this.scanIntervalMs = scanIntervalMs;
        this.phaseScheduler = phaseScheduler;
        this.backgroundScheduler = backgroundScheduler;
    }

    @PostConstruct
    public void recoverOnStartup() {
        scanner.recoverInterrupted();
        running.set(true);
        schedulePhase();
        scheduleReports();
    }

    private synchronized void schedulePhase() {
        if (!running.get()) return;
        phaseScheduler.schedule(this::pollOnce, phaseScheduler.getClock().instant().plusMillis(scanIntervalMs.getAsLong()));
    }

    private void pollOnce() {
        try {
            if (running.get()) scanner.scanOnce();
        } catch (RuntimeException ignored) {
            // A failed scan must never stop the loop; the next cycle retries.
        } finally {
            schedulePhase();
        }
    }

    private synchronized void scheduleReports() {
        if (!running.get()) return;
        backgroundScheduler.schedule(this::reportsOnce, backgroundScheduler.getClock().instant().plusMillis(scanIntervalMs.getAsLong()));
    }

    private void reportsOnce() {
        try {
            if (running.get()) scanner.recoverReports();
        } catch (RuntimeException ignored) {
            // A failed recovery pass must never stop the loop.
        } finally {
            scheduleReports();
        }
    }

    @PreDestroy
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void stop() {
        // Stop submitting before Spring's schedulers handle ContextClosedEvent.
        synchronized (this) {
            if (!running.getAndSet(false)) return;
        }
        scanner.stopAndDrain();
    }
}
