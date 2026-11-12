package com.ehe.iotagent.task;

import java.util.List;
import java.util.Map;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.task.TaskExecutionGateway.TaskExecutionRequest;
import com.ehe.iotagent.task.TaskExecutionGateway.TaskExecutionResult;
import java.util.function.BooleanSupplier;

/** Executes an already-confirmed, read-only energy assignment over fixed authorized meters. */
@FunctionalInterface
public interface TaskCheckpointRunner {
    TaskExecutionResult execute(RequestIdentity identity, TaskExecutionRequest request,
            List<String> meterIds, Map<String, Object> assignment, BooleanSupplier active);
}
