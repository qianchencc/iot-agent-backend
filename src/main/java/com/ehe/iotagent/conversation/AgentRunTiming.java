package com.ehe.iotagent.conversation;

import java.util.Map;
import java.util.function.Supplier;
import com.ehe.iotagent.conversation.internal.RunTimingRecorder;

/** Bounded, content-free timing records correlated with the engine's Langfuse runId. */
public final class AgentRunTiming {
    private AgentRunTiming() { }

    public static <T> T measure(String runId, String phase, Supplier<T> operation) {
        return RunTimingRecorder.measure(runId, phase, operation);
    }

    public static void record(String runId, String phase, long started, String outcome) {
        RunTimingRecorder.record(runId, phase, started, outcome);
    }

    public static void delivery(String runId, Map<String, Object> measurements) {
        RunTimingRecorder.delivery(runId, measurements);
    }
}
