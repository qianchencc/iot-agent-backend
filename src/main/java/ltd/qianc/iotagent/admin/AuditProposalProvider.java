package ltd.qianc.iotagent.admin;

import java.util.Map;

/**
 * Read-side seam that lets the admin audit console enrich task-related audit
 * events with the durable proposal content. Implemented by the task module so
 * the admin module never depends on task internals (task already depends on
 * admin for settings, so the reverse edge would be a module cycle).
 */
public interface AuditProposalProvider {

    /**
     * Safe projection of the proposal behind an audit event, or null when the
     * event has no resolvable proposal. Never exposes owner keys, binding
     * fingerprints or raw request bodies.
     */
    Map<String, Object> proposalDetail(String eventType, Map<String, Object> payload);
}
