package com.ehe.iotagent.conversation;

/** Business-owned run definition and public-event projection at the engine seam. */
@FunctionalInterface
public interface AgentRunPolicyProvider {
    AgentRunPolicy prepare(AgentRunRequest request);
}
