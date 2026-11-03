package ltd.qianc.iotagent.conversation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;

/** Server-bound identity and business context; never supplied by the model. */
public record AgentToolContext(
        PrincipalInfo principal,
        String runId,
        String conversationId,
        String turnId,
        String source,
        String focusRegionId,
        Map<String, Object> reference) {
    public AgentToolContext {
        reference = reference == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(reference));
    }
}
