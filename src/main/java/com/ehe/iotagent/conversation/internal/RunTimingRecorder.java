package com.ehe.iotagent.conversation.internal;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local monotonic timings only; never accepts business payloads or exception messages. */
public final class RunTimingRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(RunTimingRecorder.class);

    private RunTimingRecorder() { }

    public static <T> T measure(String runId, String phase, Supplier<T> operation) {
        long started = System.nanoTime();
        boolean completed = false;
        try {
            T result = operation.get();
            completed = true;
            return result;
        } finally {
            record(runId, phase, started, completed ? "completed" : "failed");
        }
    }

    public static void record(String runId, String phase, long started, String outcome) {
        LOG.info("[agent-latency] runId={} phase={} durationMs={} outcome={}",
                runId, phase, (System.nanoTime() - started) / 1_000_000.0, outcome);
    }

    public static void delivery(String runId, Map<String, Object> measurements) {
        LOG.info("[agent-latency] runId={} phase=java.delivery measurements={}", runId, measurements);
    }
}
