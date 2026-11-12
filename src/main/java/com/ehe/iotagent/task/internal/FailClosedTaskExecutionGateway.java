package com.ehe.iotagent.task.internal;

import com.ehe.iotagent.task.TaskExecutionGateway;

/** Prevents accidental control execution before the customer protocol is wired. */
public final class FailClosedTaskExecutionGateway implements TaskExecutionGateway {
    private final String reason;
    public FailClosedTaskExecutionGateway(String reason) { this.reason = reason; }
    @Override public TaskExecutionResult execute(TaskExecutionRequest request) {
        throw new TaskExecutionGateway.DispatchException(reason, false);
    }
}
