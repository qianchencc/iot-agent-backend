package com.ehe.iotagent.conversation;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Stable public event shape received from the Node Agent Engine. */
public record AgentEngineEvent(String runId, String conversationId, String turnId, String type, Map<String, Object> fields) {
    public AgentEngineEvent {
        fields = fields == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(fields));
    }
}
