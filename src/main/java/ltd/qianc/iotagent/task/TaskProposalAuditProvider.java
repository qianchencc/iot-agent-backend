package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.admin.AuditProposalProvider;
import ltd.qianc.iotagent.admin.AuditProjections;

/**
 * Admin audit enrichment for durable task proposals. The audit event itself
 * carries only ids and a label; this provider joins the stored proposal so the
 * console can show the reviewed task definition — name, schedule and device
 * actions — instead of bare metadata. Only whitelisted fields leave the store:
 * owner keys, binding fingerprints and raw request bodies never pass through.
 */
public final class TaskProposalAuditProvider implements AuditProposalProvider {
    private static final int MAX_TEXT = 256;

    private final TaskProposalStore proposals;
    private final ObjectMapper mapper;

    public TaskProposalAuditProvider(TaskProposalStore proposals, ObjectMapper mapper) {
        this.proposals = proposals;
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> proposalDetail(String eventType, Map<String, Object> payload) {
        TaskProposalFact proposal = null;
        Object proposalId = payload.get("proposalId");
        if (proposalId instanceof String id && !id.isBlank()) {
            try { proposal = proposals.find(id).orElse(null); } catch (RuntimeException ignored) { }
        }
        if (proposal == null && "AGENT_RUN_AWAITING_CONFIRMATION".equals(eventType)) {
            Object conversationId = payload.get("conversationId");
            Object turnId = payload.get("turnId");
            if (conversationId instanceof String cid && turnId instanceof String tid
                    && !cid.isBlank() && !tid.isBlank()) {
                try { proposal = proposals.findByConversationAndTurn(cid, tid).orElse(null); }
                catch (RuntimeException ignored) { }
            }
        }
        if (proposal == null) return null;

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("proposalId", proposal.proposalId());
        view.put("kind", proposal.kind());
        view.put("operation", proposal.operation());
        view.put("status", proposal.status());
        JsonNode taskPayload = "TASK".equals(proposal.kind())
                ? readJson(proposal.payloadJson())
                : readJson(proposal.afterJson().isBlank() ? proposal.beforeJson() : proposal.afterJson());
        if (taskPayload != null && taskPayload.isObject()) {
            putText(view, "label", taskPayload.path("label"));
            putText(view, "title", taskPayload.path("title"));
            putText(view, "taskKind", taskPayload.path("taskKind"));
            if (taskPayload.path("interval").isObject()) {
                view.put("interval", publicJson(taskPayload.path("interval")));
            }
            if (taskPayload.path("repeat").isObject()) {
                view.put("repeat", publicJson(taskPayload.path("repeat")));
            }
            List<Map<String, Object>> actions = new ArrayList<>();
            JsonNode phases = taskPayload.path("phases");
            if (phases.isArray()) {
                for (JsonNode phase : phases) {
                    String at = phase.path("at").isTextual() ? phase.path("at").asText() : null;
                    JsonNode phaseActions = phase.path("actions");
                    if (!phaseActions.isArray()) continue;
                    for (JsonNode action : phaseActions) {
                        Map<String, Object> row = AuditProjections.projectAction(
                                mapper.convertValue(action, Object.class));
                        if (row != null) {
                            if (at != null) row.put("at", AuditProjections.bound(
                                    AuditProjections.redact(at), MAX_TEXT));
                            actions.add(row);
                        }
                    }
                }
            }
            if (!actions.isEmpty()) view.put("actions", actions);
        }
        if (!"TASK".equals(proposal.kind())) {
            JsonNode target = readJson(proposal.targetJson());
            if (target != null && target.isObject()) {
                putText(view, "targetType", target.path("type"));
                JsonNode targetId = target.path("taskId").isTextual() ? target.path("taskId") : target.path("planId");
                putText(view, "targetId", targetId);
            }
            JsonNode before = readJson(proposal.beforeJson());
            if (before != null && before.isObject()) putText(view, "targetLabel", before.path("label"));
        }
        return view;
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return mapper.readTree(json); } catch (Exception e) { return null; }
    }

    private static void putText(Map<String, Object> view, String key, JsonNode node) {
        if (node != null && node.isTextual() && !node.asText().isBlank()) {
            view.put(key, AuditProjections.bound(AuditProjections.redact(node.asText()), MAX_TEXT));
        }
    }

    private Object publicJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        return mapper.convertValue(node, Object.class);
    }
}
