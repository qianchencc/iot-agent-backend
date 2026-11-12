package com.ehe.iotagent.storage.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ehe.iotagent.action.ActionDraftFact;
import com.ehe.iotagent.action.ImmediateActionHistory;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Runtime store.recordImmediate: one terminal task/phase per confirmed Office draft, never a notification. */
public final class JdbcImmediateActionHistory implements ImmediateActionHistory {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public JdbcImmediateActionHistory(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Override
    public void record(ActionDraftFact draft, AuthorizedDeviceScope scope) {
        ObjectNode body;
        try { body = (ObjectNode) mapper.readTree(draft.payloadJson()); }
        catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid completed draft", invalid); }
        if (!"office".equals(body.path("source").asText())) return;
        String key = "immediate:" + draft.draftId();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM task_facts WHERE idempotency_key=?", Integer.class, key) > 0) return;
        Set<String> selected = new HashSet<>();
        for (JsonNode id : body.path("selectedActionIds")) selected.add(id.asText());
        Map<String, JsonNode> receipts = index(body.path("result").path("receipts"));
        Map<String, JsonNode> readbacks = index(body.path("result").path("readbacks"));
        ArrayNode actions = mapper.createArrayNode();
        ObjectNode devices = mapper.createObjectNode();
        Set<String> regions = new LinkedHashSet<>();
        int success = 0;
        boolean allSatisfied = true;
        boolean partiallyAccepted = false;
        boolean interrupted = "INTERRUPTED".equals(draft.status());
        for (JsonNode action : body.path("actions")) {
            if (!selected.contains(action.path("id").asText())) continue;
            AuthorizedDevice device = scope.device(action.path("deviceId").asText()).orElseThrow();
            regions.addAll(device.officeIds());
            ObjectNode definition = ((ObjectNode) action).deepCopy();
            definition.put("deviceName", device.name());
            definition.put("location", device.installAddress());
            actions.add(definition);
            JsonNode receipt = receipts.get(action.path("id").asText());
            JsonNode readback = readbacks.get(action.path("id").asText());
            String receiptOutcome = receipt == null ? "UNKNOWN" : receipt.path("outcome").asText("UNKNOWN");
            String readbackOutcome = readback == null ? "unconfirmed" : readback.path("outcome").asText("unconfirmed");
            boolean timedOut = receipt != null && (receipt.path("timedOut").asBoolean()
                    || receipt.path("reason").asText("").contains("timeout"));
            boolean partial = "PARTIALLY_ACCEPTED".equals(receiptOutcome);
            partiallyAccepted |= partial;
            boolean accepted = "ACCEPTED".equals(receiptOutcome);
            boolean confirmed = !"access".equals(action.path("kind").asText(""))
                    && accepted && ("confirmed".equals(readbackOutcome) || "verified".equals(readbackOutcome));
            String outcome = "ALREADY_SATISFIED".equals(receiptOutcome) ? "satisfied"
                    : confirmed ? "verified" : "REJECTED".equals(receiptOutcome) ? "rejected"
                    : accepted || partial ? "unconfirmed" : "unknown";
            if ("satisfied".equals(outcome) || "verified".equals(outcome)
                    || "ACCEPTED".equals(receiptOutcome) && !timedOut) success++;
            allSatisfied &= "satisfied".equals(outcome);
            ObjectNode result = devices.putObject(device.id());
            result.put("outcome", outcome);
            result.put("actionId", action.path("id").asText());
            result.put("receipt", receiptOutcome);
            ArrayNode fields = mapper.createArrayNode();
            JsonNode fieldReceipts = receipt == null ? mapper.nullNode()
                    : receipt.path("fields").isArray() ? receipt.path("fields") : receipt.path("response").path("fields");
            for (JsonNode field : fieldReceipts) {
                ObjectNode fieldResult = fields.addObject();
                fieldResult.put("field", field.path("field").asText());
                fieldResult.put("outcome", field.path("outcome").asText().toUpperCase(java.util.Locale.ROOT));
            }
            result.set("fields", fields);
            result.put("timedOut", timedOut);
            result.set("readback", readback == null ? mapper.nullNode() : readback);
        }
        if (actions.isEmpty()) throw new IllegalArgumentException("Completed draft has no selected actions");
        String status = interrupted ? "INTERRUPTED" : partiallyAccepted ? "PARTIAL"
                : success == actions.size() ? "COMPLETED" : success > 0 ? "PARTIAL" : "FAILED";
        String phaseStatus = "COMPLETED".equals(status) && allSatisfied ? "SATISFIED" : status;
        Instant confirmedAt = Instant.parse(body.path("confirmedAt").asText());
        Instant endedAt = Instant.parse(body.path("endedAt").asText());
        String taskId = UUID.randomUUID().toString();
        String phaseId = UUID.randomUUID().toString();
        ObjectNode task = mapper.createObjectNode();
        for (String field : new String[]{"tenantCode", "ownerKey", "creatorName", "source"}) task.set(field, body.path(field));
        String summary = body.path("summary").asText("即时设备操作");
        task.put("label", summary.substring(0, summary.offsetByCodePoints(0, Math.min(12, summary.codePointCount(0, summary.length())))));
        task.put("sourceType", "immediate_draft");
        task.put("sourceDraftId", draft.draftId());
        task.put("sourceConversationId", body.path("conversationId").asText());
        task.put("createdAt", confirmedAt.toString());
        task.put("updatedAt", endedAt.toString());
        task.set("regionIds", mapper.valueToTree(regions));
        ObjectNode phase = mapper.createObjectNode();
        phase.set("actions", actions);
        ObjectNode result = mapper.createObjectNode();
        result.set("devices", devices);
        result.put("outcome", status.toLowerCase(java.util.Locale.ROOT));
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES(?,1,?,?,?,?,?)",
                    taskId, status, task.toString(), key, Timestamp.from(confirmedAt), Timestamp.from(endedAt));
            jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,result_json,claimed_at,ended_at,failure_reason,create_time,update_time) "
                            + "VALUES(?,?, 'office',1,?,?,?,?,?,?,?,?,?,?)",
                    phaseId, taskId, phaseStatus, Timestamp.from(confirmedAt), phase.toString(), phase.toString(), result.toString(),
                    Timestamp.from(confirmedAt), Timestamp.from(endedAt), interrupted ? "执行中断，部分指令结果未知" : null,
                    Timestamp.from(confirmedAt), Timestamp.from(endedAt));
        });
    }

    private static Map<String, JsonNode> index(JsonNode rows) {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows) result.put(row.path("actionId").asText(), row);
        return result;
    }
}
