package ltd.qianc.iotagent.task.internal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import ltd.qianc.iotagent.task.TaskScanner;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the MySQL phase queue with a bounded cadence and recovers in-flight facts on boot. */
public final class TaskScanLoop {
    private final TaskScanner scanner;

    public TaskScanLoop(TaskScanner scanner) { this.scanner = scanner; }

    @PostConstruct
    public void recoverOnStartup() { scanner.recoverInterrupted(); }

    @Scheduled(fixedDelayString = "${agent.task.scanner.poll-ms:1000}")
    public void poll() { scanner.scanOnce(); }

    @PreDestroy
    public void stop() { scanner.stopAndDrain(); }
}
