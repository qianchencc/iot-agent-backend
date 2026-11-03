package ltd.qianc.iotagent.pisession;

import java.util.Map;

/** Java-owned durable Pi journal, scoped by an authenticated active conversation run. */
public interface PiSessionService {
    Map<String,Object> execute(Map<String,Object> request);
    Map<String,Object> health();

    @FunctionalInterface
    interface ContextResolver { Context resolve(Map<String,Object> binding); }
    record Context(String tenant, String owner, String source, String conversationId, String runId) {}
}
