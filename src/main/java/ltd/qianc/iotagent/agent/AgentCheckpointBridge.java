package ltd.qianc.iotagent.agent;

import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.pisession.PiSessionService;
import ltd.qianc.iotagent.task.TaskCheckpointRunner;

/** Java-owned scheduled read-only invocations, sharing the existing generic Pi transport. */
public interface AgentCheckpointBridge extends TaskCheckpointRunner {
    Optional<PiSessionService.Context> sessionContext(Map<String, Object> binding);
    Optional<Map<String, Object>> executeTool(Map<String, Object> binding);
}
