package ltd.qianc.iotagent.agent;

import java.util.Map;

/** Application entry point for authenticated sidecar-to-Java business tools. */
public interface AgentToolBridge {
    Map<String, Object> execute(Map<String, Object> request);
}
