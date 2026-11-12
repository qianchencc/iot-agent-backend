package com.ehe.iotagent.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import com.ehe.iotagent.task.TaskExecutionGateway;

/** Explicit local-only task gateway for protocol acceptance; it never calls a customer system. */
public final class FixtureTaskExecutionGateway implements TaskExecutionGateway {
    private final ObjectMapper mapper;
    private final String outcome;

    public FixtureTaskExecutionGateway(ObjectMapper mapper, String outcome) {
        this.mapper = mapper;
        this.outcome = outcome == null || outcome.isBlank() ? "COMPLETED" : outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("COMPLETED", "FAILED", "UNCONFIRMED").contains(this.outcome)) {
            throw new IllegalArgumentException("unsupported fixture task outcome");
        }
    }

    @Override
    public TaskExecutionResult execute(TaskExecutionRequest request) {
        try {
            return new TaskExecutionResult(outcome, mapper.writeValueAsString(java.util.Map.of(
                    "gateway", "fixture", "taskId", request.taskId(), "phaseId", request.phaseId(),
                    "outcome", outcome, "executedAt", Instant.now().toString())), true);
        } catch (Exception error) {
            throw new IllegalStateException("fixture task result serialization failed", error);
        }
    }
}
