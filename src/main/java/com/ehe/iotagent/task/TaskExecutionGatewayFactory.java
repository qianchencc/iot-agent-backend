package com.ehe.iotagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import com.ehe.iotagent.action.ActionCommandGateway;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.task.internal.RealTaskExecutionGateway;

/** Production construction seam; execution and per-device safety remain behind TaskExecutionGateway. */
public final class TaskExecutionGatewayFactory {
    private TaskExecutionGatewayFactory() { }

    public static TaskExecutionGateway real(ObjectMapper mapper, IotAdapter iot, ActionCommandGateway actions,
            TaskExecutionIdentityStore identities, TaskDispatchGuard guard, TaskSafetyLedgerReader safetyLedger,
            Clock clock, Duration scopeTimeout, Supplier<TaskCheckpointRunner> checkpoints) {
        return new RealTaskExecutionGateway(mapper, iot, actions, identities, guard, safetyLedger,
                clock, scopeTimeout, checkpoints);
    }
}
