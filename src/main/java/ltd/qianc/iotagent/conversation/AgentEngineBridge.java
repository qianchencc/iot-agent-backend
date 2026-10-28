package ltd.qianc.iotagent.conversation;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Explicit adapter boundary for the Node Agent Engine. Implementations must not
 * manufacture assistant responses; tests may inject an isolated adapter.
 */
public interface AgentEngineBridge {

    CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events);

    CompletionStage<Void> cancel(String runId);
}
