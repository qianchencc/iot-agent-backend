package ltd.qianc.iotagent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.Executor;
import ltd.qianc.iotagent.conversation.internal.HttpAgentEngineBridge;

/** Construction entry point for the Java-to-independent-sidecar bridge. */
public final class AgentEngineBridgeFactory {
    private AgentEngineBridgeFactory() { }

    public static AgentEngineBridge http(ObjectMapper mapper, String baseUrl, String bridgeToken,
            Executor executor, Duration timeout) {
        return new HttpAgentEngineBridge(mapper, baseUrl, bridgeToken, executor, timeout);
    }
}
