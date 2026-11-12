package com.ehe.iotagent.admin.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import com.ehe.iotagent.admin.AuditProposalProvider;
import com.ehe.iotagent.admin.AuditProjections;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventQuery;
import com.ehe.iotagent.audit.AuditEventStore;
import com.ehe.iotagent.audit.AuditMember;
import com.ehe.iotagent.audit.AuditMemberIndex;
import com.ehe.iotagent.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only business audit projection for the admin console. Members, tenants
 * and events derive from audit facts only — the phone allowlist is a separate
 * capability and never feeds member or tenant discovery, and member names are
 * never borrowed: an identity without a display name renders as unknown.
 *
 * Listing is bounded SQL with a stable (at, rowId) cursor so new events cannot
 * shift pages. Detail responses are a strict whitelist projection; unknown
 * payload fields, raw tool responses, hidden reasoning and credential-like
 * text are dropped or masked. A failing audit store surfaces
 * AUDIT_UNAVAILABLE; it never degrades to an empty success.
 */
@RestController
@RequestMapping("/api/admin/audit")
public final class AuditAdminController {
    private static final int MAX_LIMIT = 200;
    private static final int MAX_TEXT = 256;
    private static final int MAX_REQUEST_REPLY = 2000;
    private static final List<String> ROW_FIELDS = List.of(
            "source", "principalId", "identityRealm", "displayName", "mobileNumber", "actor", "action", "status",
            "conversationId", "turnId", "runId", "revision", "summary");
    private static final List<String> DETAIL_SCALARS = List.of(
            "at", "source", "principalId", "identityRealm", "displayName", "mobileNumber", "actor", "action", "status",
            "conversationId", "turnId", "runId", "draftId", "taskId", "proposalId", "planId",
            "phaseId", "reportId", "cancellationId", "label", "kind", "operation", "revision",
            "summary", "question", "request", "reply", "expiresAt", "confirmedAt", "endedAt",
            "observedAt", "creatorName", "ownerKey", "dispatchStarted", "focusRegionId",
            "revalidatedInto", "code", "message", "reason", "name", "addedAt", "removedAt");
    /**
     * Event vocabulary the system actually emits, past and present. The
     * console type chooser offers only these; the list endpoint still
     * accepts any syntactically valid type so newly introduced facts stay
     * filterable before this catalog catches up.
     */
    private static final Map<String, String> EVENT_TYPE_CATALOG = catalog();
    private static final Pattern EVENT_TYPE_SYNTAX = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<String> FREE_TEXT_FIELDS = Set.of(
            "summary", "question", "request", "reply", "message", "reason");
    /** Raw payload statuses that derive to a filter result. */
    private static final Map<String, List<String>> RESULT_STATUSES = Map.ofEntries(
            Map.entry("STARTED", List.of("started")),
            Map.entry("COMPLETED", List.of("run.completed", "COMPLETED")),
            Map.entry("FAILED", List.of("run.failed", "FAILED")),
            Map.entry("ABORTED", List.of("run.aborted")),
            Map.entry("AWAITING_CONFIRMATION", List.of("run.awaiting_confirmation", "PENDING_CONFIRMATION")),
            Map.entry("PENDING_CONFIRMATION", List.of("PENDING_CONFIRMATION")));
    /** Event types that derive to a filter result when the payload has no status. */
    private static final Map<String, List<String>> RESULT_EVENT_TYPES = Map.ofEntries(
            Map.entry("STARTED", List.of("AGENT_REQUESTED")),
            Map.entry("COMPLETED", List.of("AGENT_RUN_COMPLETED")),
            Map.entry("FAILED", List.of("AGENT_RUN_FAILED", "IMMEDIATE_TASK_RECORD_FAILED")),
            Map.entry("ABORTED", List.of("AGENT_RUN_ABORTED")),
            Map.entry("AWAITING_CONFIRMATION", List.of("AGENT_RUN_AWAITING_CONFIRMATION")),
            Map.entry("DENIED", List.of("COMPANY_AGENT_DENIED")),
            Map.entry("DONE", List.of("PHONE_ALLOWLIST_ADDED", "PHONE_ALLOWLIST_REMOVED",
                    "COMPANY_AGENT_ALLOWLIST_ADDED", "COMPANY_AGENT_ALLOWLIST_REMOVED")),
            Map.entry("PENDING_CONFIRMATION", List.of(
                    "TASK_PROPOSED", "TASK_CHANGE_PROPOSED", "TASK_CANCELLATION_PROPOSED")));
    private static final Set<String> RESULT_BEARING_TYPES = new LinkedHashSet<>(
            RESULT_EVENT_TYPES.values().stream().flatMap(List::stream).toList());

    private final AuditEventStore audit;
    private final ObjectProvider<AuditProposalProvider> proposals;
    private final ObjectMapper mapper;

    public AuditAdminController(ObjectProvider<AuditEventStore> auditProvider,
            ObjectProvider<AuditProposalProvider> proposalProvider, ObjectMapper mapper) {
        this.audit = auditProvider.getIfAvailable();
        this.proposals = proposalProvider;
        this.mapper = mapper;
    }

    @GetMapping("/tenants")
    public ResponseEntity<?> tenants() {
        try {
            return ResponseEntity.ok(Map.of("tenants", List.copyOf(requireStore().tenants())));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    /** Honest chooser vocabulary: types the system emits, past and present. No store read. */
    @GetMapping("/event-types")
    public ResponseEntity<?> eventTypes() {
        List<Map<String, String>> types = new ArrayList<>();
        EVENT_TYPE_CATALOG.forEach((type, label) -> types.add(Map.of("type", type, "label", label)));
        return ResponseEntity.ok(Map.of("eventTypes", types));
    }

    /**
     * Member index for the left pane: one row per trusted identity
     * (tenant + identity realm + principal). The same principal id under two
     * realms is two members; events without a resolved identity collapse into
     * one honest unresolved bucket instead of borrowing an owner hash, the
     * allowlist or the operator as a name.
     */
    @GetMapping("/members")
    public ResponseEntity<?> members(@RequestParam String tenantCode,
            @RequestParam(required = false) String identityRealm,
            @RequestParam(required = false) String principalId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireQuery(tenantCode, limit, offset);
        AuditMemberIndex index = call(() -> requireStore().memberIndex(tenantCode,
                normalizeFilter(identityRealm), normalizeFilter(principalId), limit, offset));
        List<Map<String, Object>> members = new ArrayList<>();
        for (AuditMember member : index.members()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("tenantCode", member.tenantCode());
            view.put("identityRealm", member.identityRealm());
            view.put("principalId", member.principalId());
            view.put("identityResolved", member.identityResolved());
            view.put("displayName", member.displayName() == null
                    ? (member.identityResolved() ? null : "未识别主体") : member.displayName());
            view.put("mobileNumber", member.mobileNumber());
            view.put("eventCount", member.eventCount());
            view.put("sources", member.sources());
            view.put("firstAt", member.firstAt() == null ? null : member.firstAt().toString());
            view.put("lastAt", member.lastAt() == null ? null : member.lastAt().toString());
            members.add(view);
        }
        return ResponseEntity.ok(Map.of("members", members, "total", index.total(),
                "offset", offset, "limit", limit));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam String tenantCode,
            @RequestParam(required = false) String principalId,
            @RequestParam(required = false) String identityRealm,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String unresolved,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        requireQuery(tenantCode, limit, 0);
        String typeFilter = eventTypeFilter(eventType);
        String resultFilter = normalizeFilter(result) == null ? null
                : normalizeFilter(result).toUpperCase(java.util.Locale.ROOT);
        AuditEventQuery query = new AuditEventQuery(tenantCode,
                normalizeFilter(identityRealm),
                normalizeFilter(principalId),
                normalizeFilter(source),
                typeFilter,
                Boolean.parseBoolean(unresolved),
                instant(from, "from"),
                instant(to, "to"),
                resultStatuses(resultFilter),
                resultEventTypes(resultFilter),
                "UNKNOWN".equals(resultFilter),
                cursorAt(cursor),
                cursorId(cursor),
                limit);
        List<AuditEventFact> page = call(() -> requireStore().query(query));
        List<Map<String, Object>> events = new ArrayList<>();
        for (AuditEventFact event : page) {
            Map<String, Object> payload = parse(event.payloadJson());
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("eventId", event.eventId());
            view.put("tenantCode", event.tenantCode());
            view.put("eventType", event.eventType());
            for (String field : ROW_FIELDS) {
                if (!payload.containsKey(field)) continue;
                Object value = AuditProjections.scalar(payload.get(field), MAX_TEXT);
                if (value != null) view.put(field, value);
            }
            view.put("result", derivedResult(payload, event.eventType()));
            view.put("at", event.createdAt() == null ? null : event.createdAt().toString());
            events.add(view);
        }
        String nextCursor = page.size() < limit || page.isEmpty() ? null
                : cursorOf(page.get(page.size() - 1));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events);
        body.put("nextCursor", nextCursor);
        body.put("limit", limit);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<?> detail(@PathVariable String eventId) {
        AuditEventFact event = call(() -> requireStore().findByEventId(eventId).orElse(null));
        if (event == null) {
            return ResponseEntity.status(404)
                    .body(Map.of("code", "AUDIT_NOT_FOUND", "message", "审计记录不存在"));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("eventId", event.eventId());
        view.put("tenantCode", event.tenantCode());
        view.put("eventType", event.eventType());
        view.put("createdAt", event.createdAt() == null ? null : event.createdAt().toString());
        Map<String, Object> payload = parse(event.payloadJson());
        Map<String, Object> projected = project(payload);
        if (payload.isEmpty() && event.payloadJson() != null && !event.payloadJson().isBlank()) {
            projected.put("payloadUnreadable", true);
        }
        view.put("payload", projected);
        AuditProposalProvider provider = proposals == null ? null : proposals.getIfAvailable();
        if (provider != null) {
            Map<String, Object> proposal = provider.proposalDetail(event.eventType(), projected);
            if (proposal != null) view.put("proposal", proposal);
        }
        return ResponseEntity.ok(view);
    }

    /** Whitelist projection: only known audit fields survive, everything else is dropped. */
    private Map<String, Object> project(Map<String, Object> payload) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (String field : DETAIL_SCALARS) {
            Object value = payload.get(field);
            if (value == null) continue;
            if (value instanceof String text) {
                String bounded = AuditProjections.bound(AuditProjections.redact(text), MAX_REQUEST_REPLY);
                if (!bounded.isBlank()) view.put(field, bounded);
            } else if (value instanceof Number || value instanceof Boolean) {
                view.put(field, value);
            }
        }
        if (payload.get("counts") instanceof Map<?, ?> counts) {
            Map<String, Object> projected = new LinkedHashMap<>();
            counts.forEach((key, value) -> {
                if (key instanceof String name && (value instanceof Number || value instanceof Boolean)) {
                    projected.put(name, value);
                }
            });
            view.put("counts", projected);
        }
        if (payload.get("selectedActionIds") instanceof List<?> ids) {
            List<Object> projected = new ArrayList<>();
            for (Object id : ids) {
                Object value = AuditProjections.scalar(id, MAX_TEXT);
                if (value != null) projected.add(value);
            }
            view.put("selectedActionIds", projected);
        }
        if (payload.get("actions") instanceof List<?> actions) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (Object action : actions) {
                Map<String, Object> row = AuditProjections.projectAction(action);
                if (row != null) projected.add(row);
            }
            view.put("actions", projected);
        }
        if (payload.get("result") instanceof Map<?, ?> result) {
            view.put("result", projectResult(result));
        }
        return view;
    }


    /** Execution result keeps statuses, messages and per-action outcomes; raw device state dumps do not pass. */
    private Map<String, Object> projectResult(Map<?, ?> result) {
        Map<String, Object> view = new LinkedHashMap<>();
        Object status = result.get("status");
        if (status != null && AuditProjections.scalar(status, MAX_TEXT) != null) view.put("status", AuditProjections.scalar(status, MAX_TEXT));
        Object message = result.get("message");
        if (message instanceof String text && !text.isBlank()) view.put("message", AuditProjections.bound(AuditProjections.redact(text), MAX_REQUEST_REPLY));
        if (result.get("receipts") instanceof List<?> receipts) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (Object receipt : receipts) {
                if (!(receipt instanceof Map<?, ?> item)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : List.of("actionId", "outcome", "reason", "message")) {
                    Object value = item.get(field);
                    Object entry = AuditProjections.scalar(value, MAX_REQUEST_REPLY);
                    if (entry != null) row.put(field, entry);
                }
                if (!row.isEmpty()) projected.add(row);
            }
            view.put("receipts", projected);
        }
        if (result.get("readback") instanceof Map<?, ?> readback) {
            Map<String, Object> projected = new LinkedHashMap<>();
            readback.forEach((key, value) -> {
                if (key instanceof String name && (value instanceof String || value instanceof Number)) {
                    projected.put(AuditProjections.bound(AuditProjections.redact(name), MAX_TEXT), AuditProjections.scalar(value, MAX_TEXT));
                }
            });
            view.put("readback", projected);
        }
        return view;
    }


    private static Set<String> resultStatuses(String resultFilter) {
        if (resultFilter == null || "UNKNOWN".equals(resultFilter)) return Set.of();
        List<String> statuses = RESULT_STATUSES.get(resultFilter);
        return statuses == null ? Set.of(resultFilter) : Set.copyOf(statuses);
    }

    private static Set<String> resultEventTypes(String resultFilter) {
        if (resultFilter == null) return Set.of();
        if ("UNKNOWN".equals(resultFilter)) return RESULT_BEARING_TYPES;
        List<String> types = RESULT_EVENT_TYPES.get(resultFilter);
        return types == null ? Set.of() : Set.copyOf(types);
    }

    /**
     * Exact event_type match. Only the token shape is enforced: the value is
     * bound as a SQL parameter, so an unknown-but-valid type honestly yields
     * an empty page instead of an error, and types introduced after the
     * chooser catalog stay filterable.
     */
    private static String eventTypeFilter(String eventType) {
        String trimmed = normalizeFilter(eventType);
        if (trimmed != null && !EVENT_TYPE_SYNTAX.matcher(trimmed).matches()) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
        return trimmed;
    }

    private static Map<String, String> catalog() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("AGENT_REQUESTED", "成员请求");
        types.put("AGENT_RUN_COMPLETED", "会话完成");
        types.put("AGENT_RUN_AWAITING_CONFIRMATION", "等待成员确认");
        types.put("AGENT_RUN_FAILED", "会话失败");
        types.put("AGENT_RUN_ABORTED", "会话中止");
        types.put("ACTION_DRAFT_PROPOSED", "动作提案");
        types.put("ACTION_DRAFT_REVALIDATED", "提案复核");
        types.put("ACTION_DRAFT_ACCEPTED", "动作已执行");
        types.put("ACTION_DRAFT_PARTIALLY_ACCEPTED", "动作部分执行");
        types.put("ACTION_DRAFT_REJECTED", "动作被拒绝");
        types.put("ACTION_DRAFT_NO_CHANGE", "无需动作");
        types.put("ACTION_DRAFT_CANCELLED", "提案取消");
        types.put("ACTION_DRAFT_INTERRUPTED", "提案中断");
        types.put("ACTION_DRAFT_EXPIRED", "提案过期");
        types.put("ACTION_DRAFT_RESOLVED", "动作已完成（历史）");
        types.put("IMMEDIATE_TASK_RECORD_FAILED", "任务记录失败");
        types.put("TASK_PROPOSED", "任务提案");
        types.put("TASK_CHANGE_PROPOSED", "任务修改提案");
        types.put("TASK_CANCELLATION_PROPOSED", "任务取消提案");
        types.put("TASK_PROPOSAL_CONFIRMED", "任务提案确认");
        types.put("TASK_CHANGE_CONFIRMED", "任务修改确认");
        types.put("TASK_CANCELLATION_CONFIRMED", "任务取消确认");
        types.put("TASK_CHECKPOINT_EXECUTED", "任务检查点执行（系统）");
        types.put("TASK_OUTCOME_RECORDED", "任务终态记录（系统）");
        types.put("TASK_REPORT_PRODUCED", "任务报表产出（系统）");
        types.put("PHONE_ALLOWLIST_ADDED", "白名单添加");
        types.put("PHONE_ALLOWLIST_REMOVED", "白名单移除");
        types.put("COMPANY_AGENT_ALLOWLIST_ADDED", "白名单添加（历史）");
        types.put("COMPANY_AGENT_ALLOWLIST_REMOVED", "白名单移除（历史）");
        types.put("COMPANY_AGENT_DENIED", "公司 Agent 准入拒绝（历史）");
        return Collections.unmodifiableMap(types);
    }

    private static String derivedResult(Map<String, Object> payload, String eventType) {
        String status = text(payload.get("status"));
        if (status != null) {
            return switch (status) {
                case "run.completed" -> "COMPLETED";
                case "run.failed" -> "FAILED";
                case "run.aborted" -> "ABORTED";
                case "run.awaiting_confirmation" -> "AWAITING_CONFIRMATION";
                case "started" -> "STARTED";
                default -> status;
            };
        }
        return switch (eventType == null ? "" : eventType) {
            case "COMPANY_AGENT_DENIED" -> "DENIED";
            case "PHONE_ALLOWLIST_ADDED", "PHONE_ALLOWLIST_REMOVED",
                 "COMPANY_AGENT_ALLOWLIST_ADDED", "COMPANY_AGENT_ALLOWLIST_REMOVED" -> "DONE";
            case "IMMEDIATE_TASK_RECORD_FAILED", "AGENT_RUN_FAILED" -> "FAILED";
            case "AGENT_REQUESTED" -> "STARTED";
            case "AGENT_RUN_COMPLETED" -> "COMPLETED";
            case "AGENT_RUN_AWAITING_CONFIRMATION" -> "AWAITING_CONFIRMATION";
            case "AGENT_RUN_ABORTED" -> "ABORTED";
            case "TASK_PROPOSED", "TASK_CHANGE_PROPOSED", "TASK_CANCELLATION_PROPOSED" -> "PENDING_CONFIRMATION";
            default -> "UNKNOWN";
        };
    }

    /** Stable page cursor over (create_time, id): new inserts cannot shift or duplicate pages. */
    private static String cursorOf(AuditEventFact event) {
        return event.createdAt() + "|" + event.rowId();
    }

    private static Instant cursorAt(String cursor) {
        String at = cursorPart(cursor, 0);
        if (at == null) return null;
        try {
            return Instant.parse(at);
        } catch (RuntimeException invalid) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
    }

    private static long cursorId(String cursor) {
        String id = cursorPart(cursor, 1);
        if (id == null) return 0;
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
    }

    private static String cursorPart(String cursor, int index) {
        if (cursor == null || cursor.isBlank()) return null;
        String[] parts = cursor.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
        return parts[index];
    }

    private AuditEventStore requireStore() {
        if (audit == null) throw unavailable();
        return audit;
    }

    private <T> T call(Read<T> read) {
        try {
            return read.get();
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException readFailure) {
            throw unavailable();
        }
    }

    private interface Read<T> { T get(); }

    private static BusinessException unavailable() {
        return new BusinessException("AUDIT_UNAVAILABLE", "审计存储不可用，请稍后重试", 503);
    }

    private static void requireQuery(String tenantCode, int limit, int offset) {
        if (tenantCode == null || tenantCode.isBlank() || tenantCode.length() > 128
                || limit < 1 || limit > MAX_LIMIT || offset < 0) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
    }

    private static String normalizeFilter(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_TEXT) throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        return trimmed;
    }

    private static Instant instant(String value, String field) {
        String trimmed = normalizeFilter(value);
        if (trimmed == null) return null;
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException invalid) {
            throw new BusinessException("AUDIT_INVALID", "审计查询参数无效", 400);
        }
    }

    private static String text(Object value) {
        if (value instanceof String string && !string.isBlank()) return string;
        return null;
    }


    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception unreadable) {
            return Map.of();
        }
    }
}
