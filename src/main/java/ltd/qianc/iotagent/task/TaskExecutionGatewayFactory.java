package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.task.internal.RealTaskExecutionGateway;

/** Public construction seam for the production-shape task execution adapter. */
public final class TaskExecutionGatewayFactory {
    private TaskExecutionGatewayFactory() { }

    public static TaskExecutionGateway real(ObjectMapper mapper, IotAdapter iot, ActionCommandGateway actions,
                                            TaskExecutionIdentityStore identities, Clock clock,
                                            Duration scopeTimeout) {
        return new RealTaskExecutionGateway(mapper, iot, actions, identities, clock, scopeTimeout);
    }
}
