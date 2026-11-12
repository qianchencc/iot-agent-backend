package com.ehe.iotagent.storage.internal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.ehe.iotagent.task.TaskOccurrenceMaterializer;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls enabled MySQL plans with a bounded batch; no process-local recurrence state. */
public final class JdbcTaskRecurrenceLoop {
    private final TaskOccurrenceMaterializer materializer;

    public JdbcTaskRecurrenceLoop(TaskOccurrenceMaterializer materializer) { this.materializer = materializer; }

    @PostConstruct
    public void materializeOnStartup() { materializer.materializeOnce(); }

    @Scheduled(fixedDelayString = "${agent.task.recurrence.poll-ms:1000}")
    public void poll() { materializer.materializeOnce(); }

    @PreDestroy
    public void stop() { }
}
