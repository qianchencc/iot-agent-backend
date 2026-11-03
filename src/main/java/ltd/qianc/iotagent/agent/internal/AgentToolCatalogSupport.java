package ltd.qianc.iotagent.agent.internal;

import java.util.Map;

/** Narrow internal helper for the transport envelope; not business dispatch. */
public final class AgentToolCatalogSupport {
    private AgentToolCatalogSupport() { }

    public static boolean haltsRun(String toolName, Map<String, Object> result) {
        if (toolName == null || result == null) return false;
        if (("finish_checkpoint".equals(toolName) || "publish_report".equals(toolName))
                && "submitted".equals(result.get("status"))) return true;
        AgentToolCatalog.Definition definition = AgentToolCatalog.definition(toolName);
        if (definition == null || definition.confirmation() == AgentToolCatalog.Confirmation.NONE) return false;
        Object status = result.get("status");
        return status == null || "PENDING_CONFIRMATION".equalsIgnoreCase(String.valueOf(status));
    }
}
