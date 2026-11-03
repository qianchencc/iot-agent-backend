package ltd.qianc.iotagent.conversation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One run's data-only engine request and stateful Java event projection. */
public record AgentRunPolicy(
        Map<String, Object> request,
        Function<AgentEngineEvent, List<AgentEngineEvent>> projection) {
    public AgentRunPolicy {
        request = Map.copyOf(request);
        Objects.requireNonNull(projection, "projection");
    }
}
