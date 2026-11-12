package com.ehe.iotagent.task;

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
        /** The task's fixed device catalog left the authorized scope (Runtime authorization_revoked). */
        public static final String AUTHORIZATION_REVOKED = "authorization_revoked";
        /** A control binding fingerprint changed (Runtime binding_changed). */
        public static final String BINDING_CHANGED = "binding_changed";
        /** Upstream rejected the stored creator identity (Runtime identity_rejected). */
        public static final String IDENTITY_REJECTED = "identity_rejected";

        private final boolean dispatchStarted;
        private final String reasonCode;

        public DispatchException(String message, boolean dispatchStarted) {
            this(message, null, dispatchStarted, null);
        }

        public DispatchException(String message, Throwable cause, boolean dispatchStarted) {
            this(message, cause, dispatchStarted, null);
        }

        public DispatchException(String message, boolean dispatchStarted, String reasonCode) {
            this(message, null, dispatchStarted, reasonCode);
        }

        public DispatchException(String message, Throwable cause, boolean dispatchStarted, String reasonCode) {
            super(message, cause);
            this.dispatchStarted = dispatchStarted;
            this.reasonCode = reasonCode;
        }

        public boolean dispatchStarted() { return dispatchStarted; }

        /**
         * Deterministic invalidation cause for recurring plans, or null for
         * ordinary transient failures. The scanner disables the source plan
         * only for these codes, exactly like the Runtime's disablePlan kinds.
         */
        public String reasonCode() { return reasonCode; }
    }
}
