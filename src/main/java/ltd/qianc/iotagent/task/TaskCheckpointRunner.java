package ltd.qianc.iotagent.task;

import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskExecutionGateway.TaskExecutionRequest;
import ltd.qianc.iotagent.task.TaskExecutionGateway.TaskExecutionResult;
import java.util.function.BooleanSupplier;

/** Executes an already-confirmed, read-only energy assignment over fixed authorized meters. */
@FunctionalInterface
public interface TaskCheckpointRunner {
    TaskExecutionResult execute(RequestIdentity identity, TaskExecutionRequest request,
            List<String> meterIds, Map<String, Object> assignment, BooleanSupplier active);
}
