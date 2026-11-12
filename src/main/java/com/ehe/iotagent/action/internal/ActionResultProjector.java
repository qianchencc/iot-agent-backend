package com.ehe.iotagent.action.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ehe.iotagent.action.ActionDraftFact;

/**
 * The single truthful projection of a stored execution result. Legacy persisted
 * results predate the status/message contract; they are normalized at read time
 * from the authoritative terminal status plus the receipts, per-receipt fields
 * and readbacks that were actually recorded. History is never rewritten and no
 * fresh device state is consulted, so a door can never be "confirmed" from a
 * later open/closed reading.
 *
 * <p>Device, receipt and field classification live here once; every read path
 * (confirm response, draft detail, conversation restore, persisted receipts)
 * shares this projection instead of growing parallel taxonomies.</p>
 */
public final class ActionResultProjector {
    private final ObjectMapper mapper;

    public ActionResultProjector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Receipt truth classes shared by every caller; wording is decided from these, never re-derived. */
    private enum ReceiptClassEnum { REFUSED, UNKNOWN, PARTIAL, SENT, CONFIRMED, SATISFIED }

    /** Open-once access control never yields physical confirmation evidence for a dispatch. */
    public static boolean noPhysicalConfirmation(String kind) {
        return "access".equals(kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Normalized nested result for a draft, or {@code null} when the draft never
     * executed (pending, cancelled, expired): those must not fabricate an execution.
     */
    public ObjectNode project(ActionDraftFact draft, ObjectNode payload) {
        JsonNode stored = payload.get("result");
        ObjectNode result = stored != null && stored.isObject() ? (ObjectNode) stored : null;
        if (result == null && !executed(draft.status())) return null;
        return normalized(result, draft.draftId(), draft.status(),
                kindsByAction(payload), devicesByAction(payload));
    }

    /**
     * Flat confirm-answer body for a draft that holds no executed result: the
     * truthful draft status, never a fabricated execution.
     */
    public Map<String, Object> statusBody(ActionDraftFact draft) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("draftId", draft.draftId());
        body.put("status", draft.status());
        body.put("message", switch (draft.status()) {
            case "CANCELLED" -> "草案已取消";
            case "EXPIRED" -> "草案已过期";
            default -> "";
        });
        return body;
    }

    /**
     * Reprojects a locally parsed result in place from the authoritative draft
     * status and recorded receipt evidence. Gateway responses and errors remain
     * intact. The Runtime flat legacy shape carries recorded readback/state maps
     * without raw records; those maps are preserved and feed classification.
     */
    public ObjectNode normalized(ObjectNode stored, String draftId, String authoritativeStatus,
                                 Map<String, String> kindsByAction, Map<String, String> devicesByAction) {
        ObjectNode result = stored == null ? mapper.createObjectNode() : stored;
        JsonNode records = result.get("readbacks");
        Map<String, String> readbackByAction = new LinkedHashMap<>();
        if (records != null && records.isArray()) {
            for (JsonNode record : records) {
                String actionId = record.path("actionId").asText("");
                if (!actionId.isBlank()) readbackByAction.put(actionId, record.path("outcome").asText(""));
            }
        } else if (result.path("readback").isObject()) {
            JsonNode recorded = result.get("readback");
            for (Iterator<String> names = recorded.fieldNames(); names.hasNext(); ) {
                String actionId = names.next();
                JsonNode outcomeRecord = recorded.get(actionId);
                if (outcomeRecord.isTextual()) readbackByAction.put(actionId, outcomeRecord.asText());
            }
        }
        JsonNode storedReceipts = result.get("receipts");
        List<ReceiptClassEnum> classes = new ArrayList<>();
        if (storedReceipts != null && storedReceipts.isArray()) {
            for (JsonNode node : storedReceipts) {
                if (!(node instanceof ObjectNode receipt)) continue;
                normalizeFields(receipt);
                String actionId = receipt.path("actionId").asText("");
                ReceiptClassEnum receiptClass = classify(receipt, kindsByAction.getOrDefault(actionId, ""),
                        readbackByAction.getOrDefault(actionId, ""));
                classes.add(receiptClass);
                JsonNode fields = receipt.get("fields");
                if (fields != null && fields.isArray() && !fields.isEmpty()) {
                    // Field-derived truth reaches the per-device UI through the receipt
                    // outcome while the raw response evidence stays as recorded.
                    receipt.put("outcome", displayOutcome(receiptClass));
                }
            }
        }
        if (records != null && records.isArray()) {
            ObjectNode readback = mapper.createObjectNode();
            ObjectNode observedStates = mapper.createObjectNode();
            String observedAt = null;
            for (JsonNode record : records) {
                String actionId = record.path("actionId").asText("");
                if (actionId.isBlank()) continue;
                readback.put(actionId, record.path("outcome").asText("unconfirmed"));
                if (record.has("observedState")) {
                    String deviceId = devicesByAction.get(actionId);
                    if (deviceId != null) observedStates.set(deviceId, record.get("observedState"));
                }
                if (record.has("observedAt")) observedAt = record.path("observedAt").asText(observedAt);
            }
            result.set("readback", readback);
            result.set("observedStates", observedStates);
            if (observedAt != null) result.put("observedAt", observedAt);
        }
        result.put("draftId", draftId);
        // The authoritative draft status wins over any stale stored label: an
        // "EXECUTING" result on a terminal draft must never read as executing.
        // Aggregate message/outcome are canonically re-derived from the same
        // status plus the recorded evidence, so an old contradictory label
        // cannot outlive its facts; the raw evidence fields stay untouched.
        result.put("status", authoritativeStatus);
        result.put("message", message(authoritativeStatus, classes));
        result.put("outcome", outcome(authoritativeStatus, classes));
        return result;
    }

    private static String displayOutcome(ReceiptClassEnum receiptClass) {
        return switch (receiptClass) {
            case REFUSED -> "REJECTED";
            case UNKNOWN -> "UNKNOWN";
            case PARTIAL -> "PARTIALLY_ACCEPTED";
            case CONFIRMED -> "DEVICE_APPLIED";
            case SENT -> "ACCEPTED";
            case SATISFIED -> "ALREADY_SATISFIED";
        };
    }

    public Map<String, String> kindsByAction(ObjectNode payload) {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (JsonNode action : payload.path("actions")) {
            String actionId = action.path("id").asText("");
            if (!actionId.isBlank()) kinds.put(actionId, action.path("kind").asText(""));
        }
        return kinds;
    }

    public Map<String, String> devicesByAction(ObjectNode payload) {
        Map<String, String> devices = new LinkedHashMap<>();
        for (JsonNode action : payload.path("actions")) {
            String actionId = action.path("id").asText("");
            String deviceId = action.path("deviceId").asText("");
            if (!actionId.isBlank() && !deviceId.isBlank()) devices.put(actionId, deviceId);
        }
        return devices;
    }

    private void normalizeFields(ObjectNode receipt) {
        JsonNode fields = receipt.get("fields");
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            // Legacy gateway evidence kept per-field AC results nested under response.fields.
            JsonNode legacy = receipt.path("response").path("fields");
            if (legacy.isArray() && !legacy.isEmpty()) receipt.set("fields", normalizedFields(legacy));
            return;
        }
        for (JsonNode field : fields) {
            String raw = field.path("outcome").asText("");
            if (!raw.equals(raw.trim().toUpperCase(Locale.ROOT))) {
                receipt.set("fields", normalizedFields(fields));
                return;
            }
        }
    }

    private ArrayNode normalizedFields(JsonNode fields) {
        ArrayNode normalized = mapper.createArrayNode();
        for (JsonNode field : fields) {
            ObjectNode copy = field.isObject() ? (ObjectNode) field.deepCopy() : mapper.createObjectNode();
            copy.put("outcome", field.path("outcome").asText("").trim().toUpperCase(Locale.ROOT));
            normalized.add(copy);
        }
        return normalized;
    }

    private ReceiptClassEnum classify(JsonNode receipt, String kind, String readbackOutcome) {
        JsonNode fields = receipt.get("fields");
        if (fields == null || !fields.isArray() || fields.isEmpty()) {
            return classifyOutcome(receipt.path("outcome").asText(""), kind, readbackOutcome);
        }
        // Per-field evidence is the finer truth: legacy gateways derived the outer
        // receipt outcome from a coarse code, so a "REJECTED" receipt may still
        // carry accepted parameters. Persisted facts stay untouched; only the
        // classification reads the fields.
        boolean refused = false;
        boolean sent = false;
        boolean confirmed = false;
        boolean partial = false;
        boolean unknown = false;
        for (JsonNode field : fields) {
            switch (classifyOutcome(field.path("outcome").asText(""), kind, readbackOutcome)) {
                case REFUSED -> refused = true;
                case UNKNOWN -> unknown = true;
                case PARTIAL -> partial = true;
                case SENT -> sent = true;
                case CONFIRMED -> confirmed = true;
                case SATISFIED -> { }
            }
        }
        if (partial || (sent || confirmed) && (refused || unknown)) return ReceiptClassEnum.PARTIAL;
        if (unknown) return ReceiptClassEnum.UNKNOWN;
        if (refused) return ReceiptClassEnum.REFUSED;
        if (sent) return ReceiptClassEnum.SENT;
        if (confirmed) return ReceiptClassEnum.CONFIRMED;
        return ReceiptClassEnum.SATISFIED;
    }

    private ReceiptClassEnum classifyOutcome(String outcome, String kind, String readbackOutcome) {
        switch (outcome == null ? "" : outcome.trim().toUpperCase(Locale.ROOT)) {
            case "REJECTED":
            case "NOT_DISPATCHED":
                return ReceiptClassEnum.REFUSED;
            case "TIMEOUT":
            case "UNKNOWN":
                return ReceiptClassEnum.UNKNOWN;
            case "ALREADY_SATISFIED":
                return ReceiptClassEnum.SATISFIED;
            case "PARTIALLY_ACCEPTED":
                return ReceiptClassEnum.PARTIAL;
            case "DEVICE_APPLIED":
                return noPhysicalConfirmation(kind) ? ReceiptClassEnum.SENT : ReceiptClassEnum.CONFIRMED;
            case "ACCEPTED":
                if (noPhysicalConfirmation(kind)) return ReceiptClassEnum.SENT;
                return confirmedReadback(readbackOutcome) ? ReceiptClassEnum.CONFIRMED : ReceiptClassEnum.SENT;
            default:
                return ReceiptClassEnum.UNKNOWN;
        }
    }


    private static boolean confirmedReadback(String readbackOutcome) {
        String value = readbackOutcome == null ? "" : readbackOutcome.trim().toLowerCase(Locale.ROOT);
        return "confirmed".equals(value) || "satisfied".equals(value);
    }

    private static boolean executed(String status) {
        return "ACCEPTED".equals(status) || "PARTIALLY_ACCEPTED".equals(status) || "REJECTED".equals(status)
                || "NO_CHANGE".equals(status) || "INTERRUPTED".equals(status);
    }

    private static String message(String status, List<ReceiptClassEnum> classes) {
        if ("INTERRUPTED".equals(status)) return "下发结果未知";
        boolean unknown = false;
        boolean partial = false;
        boolean refused = false;
        boolean confirmed = false;
        boolean dispatched = false;
        boolean satisfied = false;
        for (ReceiptClassEnum cls : classes) {
            switch (cls) {
                case UNKNOWN -> unknown = true;
                case PARTIAL -> partial = true;
                case REFUSED -> refused = true;
                case CONFIRMED -> confirmed = true;
                case SENT -> dispatched = true;
                case SATISFIED -> satisfied = true;
            }
        }
        if ("NO_CHANGE".equals(status)) return refused ? "无需操作，有未受理项" : "无需操作";
        // Unknown transport is not proof of a dispatch: only a known accepted or
        // partial receipt turns a mix into "partially dispatched".
        if (unknown) {
            if (partial || confirmed || dispatched) return "部分已下发，未全部成功";
            if (refused) return "下发结果未知，有未受理项";
            return "下发结果未知";
        }
        if (partial || refused && (confirmed || dispatched)) return "部分已下发，未全部成功";
        if (refused) return satisfied ? "无需操作，有未受理项" : "未受理";
        if (confirmed && !dispatched) return "已完成";
        if (confirmed || dispatched) return "已下发";
        if (satisfied) return "无需操作";
        return switch (status) {
            case "REJECTED" -> "未受理";
            case "NO_CHANGE" -> "无需操作";
            case "PARTIALLY_ACCEPTED" -> "部分已下发，未全部成功";
            case "ACCEPTED" -> "已下发";
            case "EXECUTING" -> "正在执行，请稍候";
            default -> "下发结果未知";
        };
    }

    private static String outcome(String status, List<ReceiptClassEnum> classes) {
        if ("INTERRUPTED".equals(status)) return "UNKNOWN";
        if ("NO_CHANGE".equals(status)) return "ALREADY_SATISFIED";
        boolean unknown = false;
        boolean partial = false;
        boolean refused = false;
        boolean confirmed = false;
        boolean dispatched = false;
        boolean satisfied = false;
        for (ReceiptClassEnum cls : classes) {
            switch (cls) {
                case UNKNOWN -> unknown = true;
                case PARTIAL -> partial = true;
                case REFUSED -> refused = true;
                case CONFIRMED -> confirmed = true;
                case SENT -> dispatched = true;
                case SATISFIED -> satisfied = true;
            }
        }
        if (unknown && (partial || confirmed || dispatched)) return "PARTIALLY_ACCEPTED";
        if (unknown) return "UNKNOWN";
        if (partial || refused && (confirmed || dispatched || satisfied)) return "PARTIALLY_ACCEPTED";
        if (refused) return "REJECTED";
        if (confirmed && !dispatched) return "DEVICE_APPLIED";
        if (dispatched) return "ACCEPTED";
        if (satisfied) return "ALREADY_SATISFIED";
        return switch (status) {
            case "REJECTED" -> "REJECTED";
            case "PARTIALLY_ACCEPTED" -> "PARTIALLY_ACCEPTED";
            case "ACCEPTED" -> "ACCEPTED";
            default -> "UNKNOWN";
        };
    }
}
