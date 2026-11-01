package ltd.qianc.iotagent.task;

import java.time.Instant;

/**
 * Explicit adapter for real task execution. This module never invents a device
 * protocol and never executes through a local/in-memory substitute.
 */
public interface TaskExecutionGateway {
    TaskExecutionResult execute(TaskExecutionRequest request);

    record TaskExecutionRequest(String taskId, String phaseId, String source,
                                String payloadJson, Instant dueAt, String taskPayloadJson) {
        public TaskExecutionRequest(String taskId, String phaseId, String source,
                                    String payloadJson, Instant dueAt) {
            this(taskId, phaseId, source, payloadJson, dueAt, "{}");
        }

        public TaskExecutionRequest {
            if (taskId == null || taskId.isBlank() || phaseId == null || phaseId.isBlank()) {
                throw new IllegalArgumentException("task and phase are required");
            }
            if (source == null || source.isBlank() || payloadJson == null || payloadJson.isBlank() || dueAt == null) {
                throw new IllegalArgumentException("execution request is incomplete");
            }
            taskPayloadJson = taskPayloadJson == null || taskPayloadJson.isBlank() ? "{}" : taskPayloadJson;
        }
    }

    record TaskExecutionResult(String outcome, String payloadJson, boolean dispatchStarted) {
        public TaskExecutionResult {
            if (outcome == null || outcome.isBlank() || payloadJson == null || payloadJson.isBlank()) {
                throw new IllegalArgumentException("execution result is incomplete");
            }
        }
    }
    /**
     * Gateway failures may identify that dispatch definitely never crossed the
     * customer boundary. The scanner can then record a normal failed phase
     * instead of an unknown result.
     */
    final class DispatchException extends RuntimeException {
        private final boolean dispatchStarted;

        public DispatchException(String message, boolean dispatchStarted) {
            super(message);
            this.dispatchStarted = dispatchStarted;
        }

        public DispatchException(String message, Throwable cause, boolean dispatchStarted) {
            super(message, cause);
            this.dispatchStarted = dispatchStarted;
        }

        public boolean dispatchStarted() { return dispatchStarted; }
    }
}
