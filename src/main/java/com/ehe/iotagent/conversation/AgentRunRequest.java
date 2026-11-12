package com.ehe.iotagent.conversation;

import java.util.Map;

/** Request sent across the Java-to-Node Agent Engine seam. */
public record AgentRunRequest(
        String runId,
        String conversationId,
        String turnId,
        String source,
        String content,
        String tenantCode,
        String focusRegionId,
        Map<String, Object> reference,
        String toolContextId) {
}
