package com.ehe.iotagent.task.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import com.ehe.iotagent.auth.PrincipalContext;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.error.RequestValues;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskProposalFact;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime-compatible task, plan and inbox routes; all state moves stay in TaskFacade.
 * 请求契约（source/version/operation 的类型与错误码）见 docs/api/java-contract.md。
 */
@RestController
@RequestMapping("/api/agent")
public final class TaskController {
    private final TaskFacade facade;
    private final ObjectMapper mapper;
    private final com.ehe.iotagent.task.TaskProjectionStore projections;
    private final com.ehe.iotagent.task.TaskConfirmationService confirmations;
    private final com.ehe.iotagent.allowlist.PhoneAllowlist allowlist;
    public TaskController(TaskFacade facade, ObjectMapper mapper, com.ehe.iotagent.task.TaskProjectionStore projections,
            com.ehe.iotagent.task.TaskConfirmationService confirmations,
            com.ehe.iotagent.allowlist.PhoneAllowlist allowlist) {
        this.facade = facade; this.mapper = mapper; this.projections = projections; this.confirmations = confirmations;
        this.allowlist = allowlist;
    }

    @org.springframework.web.bind.annotation.ModelAttribute
    public void authorizeCompanyTasks(@RequestParam(required = false) String source,
            jakarta.servlet.http.HttpServletRequest request) {
        // 通知独立保留；任务正文中的 source 在 source(body) 统一核验。
        if (!request.getRequestURI().startsWith("/api/agent/notifications")) {
            allowlist.requireCompanyTasks(PrincipalContext.getPrincipalInfo(), source);
        }
    }

    /**
     * 提交任务提案。请求字段：{@code payload}（任务内容对象或 JSON 字符串）、{@code source}
     * （可缺省为 office；出现即必须是字符串，非法类型 400 INVALID_AGENT_SOURCE）、
     * {@code conversationId}/{@code turnId}/{@code idempotencyKey}（可缺省；出现即必须是字符串，
     * 否则 400 INVALID_PARAMETERS）。payload 非法返回 400 INVALID_TASK_PAYLOAD。
     */
    @PostMapping("/task-proposals")
    public Map<String, Object> propose(@RequestBody Map<String, Object> body) {
        String payload = json(body.get("payload"));
        TaskProposalFact proposal = facade.propose(PrincipalContext.getPrincipalInfo(), source(body),
                text(body, "conversationId", ""), text(body, "turnId", ""), payload, text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }
    /** 提案详情；query {@code source} 可缺省，非法值 400 INVALID_AGENT_SOURCE。 */
    @GetMapping("/task-proposals/{id}")
    public Map<String, Object> proposal(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }

    /**
     * 确认提案并创建任务。请求字段：{@code version}（必需，>=1 的无损整数——缺失、显式 null、
     * 小数（含 1.0/1e0）、字符串与溢出统一 400 INVALID_VERSION，绝不截断为 1）。
     */
    @PostMapping("/task-proposals/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @RequestParam(required = false) String source,
                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return confirmations.confirm(principal, id, version(body), source);
    }
    /** 丢弃提案（幂等返回 CANCELLED）；{@code version} 契约同 confirm。 */
    @PostMapping("/task-proposals/{id}/discard")
    public Map<String, Object> discard(@PathVariable String id, @RequestParam(required = false) String source,
                                       @RequestBody(required = false) Map<String, Object> body) {
        discardProposal(id, source, body);
        return Map.of("taskId", id, "status", "CANCELLED");
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/confirm")
    public Map<String, Object> confirmTask(@PathVariable String id, @RequestParam(required = false) String source,
                                           @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return confirmations.confirm(principal, id, version(body), source);
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/discard")
    public Map<String, Object> discardTask(@PathVariable String id, @RequestParam(required = false) String source,
                                           @RequestBody(required = false) Map<String, Object> body) {
        discardProposal(id, source, body);
        return Map.of("taskId", id, "status", "CANCELLED");
    }

    private void discardProposal(String id, String source, Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        if (source == null) facade.discard(principal, id, version(body));
        else facade.discard(principal, id, version(body), source);
    }

    /** 任务变更提案详情（与 task-proposals 同一投影）。 */
    @GetMapping("/task-changes/{id}")
    public Map<String, Object> taskChange(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }
    /** 任务取消提案详情（与 task-proposals 同一投影）。 */
    @GetMapping("/task-cancellations/{id}")
    public Map<String, Object> taskCancellation(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }

    /**
     * 提交任务变更提案。body 即变更内容（含 targetTaskId 等）；source/conversationId/turnId/
     * idempotencyKey 的类型契约同 {@link #propose}。
     */
    @PostMapping("/task-changes")
    public Map<String, Object> proposeTaskChange(@RequestBody Map<String, Object> body) {
        TaskProposalFact proposal = facade.proposeChange(PrincipalContext.getPrincipalInfo(), source(body),
                text(body, "conversationId", ""), text(body, "turnId", ""), json(body), text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }

    /**
     * 确认任务变更。请求字段：{@code version}（可缺省为 1 的历史兼容默认；键存在即必须是
     * >=1 的无损整数——小数（含 1.0）、字符串、溢出与显式 null 返回 400 INVALID_VERSION，
     * 不再被静默替换成 1）。
     */
    @PostMapping("/task-changes/{id}/confirm")
    public Map<String, Object> confirmTaskChange(@PathVariable String id, @RequestParam(required = false) String source,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return source == null ? facade.confirmChange(principal, id, versionOrDefault(body))
                : facade.confirmChange(principal, id, versionOrDefault(body), source);
    }
    /** 丢弃任务变更；{@code version} 兼容默认契约同 confirmTaskChange。 */
    @PostMapping("/task-changes/{id}/discard")
    public Map<String, Object> discardTaskChange(@PathVariable String id, @RequestParam(required = false) String source,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        TaskProposalFact discarded = source == null ? facade.discardChange(principal, id, versionOrDefault(body))
                : facade.discardChange(principal, id, versionOrDefault(body), source);
        return Map.of("changeId", discarded.proposalId(), "status", "CANCELLED");
    }

    /**
     * 提交任务取消提案；source/conversationId/turnId/idempotencyKey 的类型契约同
     * {@link #propose}。
     */
    @PostMapping("/task-cancellations")
    public Map<String, Object> proposeTaskCancellation(@RequestBody Map<String, Object> body) {
        TaskProposalFact proposal = facade.proposeCancellation(PrincipalContext.getPrincipalInfo(), source(body),
                text(body, "conversationId", ""), text(body, "turnId", ""), json(body), text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }
    /** 确认任务取消提案；{@code version} 兼容默认契约同 confirmTaskChange。 */
    @PostMapping("/task-cancellations/{id}/confirm")
    public Map<String, Object> confirmTaskCancellation(@PathVariable String id, @RequestParam(required = false) String source,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return source == null ? facade.confirmCancellation(principal, id, versionOrDefault(body))
                : facade.confirmCancellation(principal, id, versionOrDefault(body), source);
    }
    /** 丢弃任务取消提案；{@code version} 兼容默认契约同 confirmTaskChange。 */
    @PostMapping("/task-cancellations/{id}/discard")
    public Map<String, Object> discardTaskCancellation(@PathVariable String id, @RequestParam(required = false) String source,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        TaskProposalFact discarded = source == null ? facade.discardCancellation(principal, id, versionOrDefault(body))
                : facade.discardCancellation(principal, id, versionOrDefault(body), source);
        return Map.of("cancellationId", discarded.proposalId(), "status", "CANCELLED");
    }


    /** 任务列表；from/to 为 ISO instant，默认最近 31 天，最多 62 天，越界 400 INVALID_DATE_RANGE。 */
    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "office") String source,
                                     @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        DateRange range = dateTimeRange(from, to);
        return Map.of("tasks", projection().list(PrincipalContext.getPrincipalInfo(), source, range.from(), range.to()));
    }
    /** 区域时间轴（单日，Asia/Shanghai）；date 或 from 非法 400 INVALID_DATE。 */
    @GetMapping("/regions/{regionId}/timeline")
    public Map<String, Object> regionTimeline(@PathVariable String regionId,
                                              @RequestParam(required = false) String date,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(defaultValue = "office") String source) {
        LocalDate day = date != null ? localDate(date, "请选择有效日期") : localDateBoundary(from, "请选择有效日期");
        Instant start = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        return Map.of("date", day.toString(), "tasks", projection().regionTimeline(PrincipalContext.getPrincipalInfo(), source, regionId,
                start, start.plus(Duration.ofDays(1))));
    }
    /** 区域日历（from..to 日期，最长 62 天）；非法 400 INVALID_DATE_RANGE。 */
    @GetMapping("/regions/{regionId}/calendar")
    public Map<String, Object> regionCalendar(@PathVariable String regionId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        LocalDate fromDate = localDateBoundary(from, "请选择有效日期范围"), toDate = localDateBoundary(to, "请选择有效日期范围");
        if (toDate.isBefore(fromDate) || toDate.toEpochDay() - fromDate.toEpochDay() > 62) {
            throw new BusinessException("INVALID_DATE_RANGE", "请选择有效日期范围", 400);
        }
        Instant start = fromDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        return Map.of("from", fromDate.toString(), "to", toDate.toString(), "days",
                projection().regionCalendar(PrincipalContext.getPrincipalInfo(), source, regionId, start, end));
    }
    /** 设备时间轴（单日）；date/from 非法 400 INVALID_DATE。 */
    @GetMapping("/devices/{deviceId}/timeline")
    public Map<String, Object> deviceTimeline(@PathVariable String deviceId,
                                              @RequestParam(required = false) String date,
                                              @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                              @RequestParam(defaultValue = "office") String source) {
        LocalDate day = date != null ? localDate(date, "请选择有效日期") : localDateBoundary(from, "请选择有效日期");
        Instant start = day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        return Map.of("date", day.toString(), "events", projection().deviceTimeline(PrincipalContext.getPrincipalInfo(), source, deviceId,
                start, start.plus(Duration.ofDays(1))));
    }
    /** 设备日历（from..to，最长 62 天）；非法 400 INVALID_DATE_RANGE。 */
    @GetMapping("/devices/{deviceId}/calendar")
    public Map<String, Object> deviceCalendar(@PathVariable String deviceId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        LocalDate fromDate = localDateBoundary(from, "请选择有效日期范围"), toDate = localDateBoundary(to, "请选择有效日期范围");
        if (toDate.isBefore(fromDate) || toDate.toEpochDay() - fromDate.toEpochDay() > 62) {
            throw new BusinessException("INVALID_DATE_RANGE", "请选择有效日期范围", 400);
        }
        Instant start = fromDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant end = toDate.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        return Map.of("from", fromDate.toString(), "to", toDate.toString(), "days",
                projection().deviceCalendar(PrincipalContext.getPrincipalInfo(), source, deviceId, start, end));
    }
    /** 任务详情；query {@code source} 可缺省为 office，非法值 400 INVALID_AGENT_SOURCE。 */
    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        String requestedSource = source == null ? "office" : source;
        return Map.of("task", facade.taskView(principal, id, requestedSource));
    }
    /** 任务覆盖照明当前状态；query {@code source} 可缺省。 */
    @GetMapping("/tasks/{id}/lighting-states")
    public Map<String, Object> lightingStates(@PathVariable String id,
                                               @RequestParam(defaultValue = "office") String source) {
        return facade.lightingStates(PrincipalContext.getPrincipalInfo(), id, source);
    }

    /**
     * 取消任务。请求字段：{@code version}（必需，>=1 的无损整数；缺失、小数（含 1.0）、字符串、
     * 溢出统一 400 INVALID_VERSION——不再把 1.9 截断成 1 透传给 facade）。
     */
    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestParam(required = false) String source,
                                      @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        var cancelled = source == null ? facade.cancel(principal, id, version(body))
                : facade.cancel(principal, id, version(body), source);
        return Map.of("taskId", cancelled.taskId(), "status", "cancelled", "version", cancelled.version());
    }

    /** 通知收件箱；limit 为正整数（默认 30）。 */
    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestParam(defaultValue = "office") String source,
                                             @RequestParam(defaultValue = "30") int limit,
                                             @RequestParam(required = false) String before) {
        return facade.notificationsView(PrincipalContext.getPrincipalInfo(), source, limit, before);
    }
    /** 标记单条通知已读。 */
    @PostMapping("/notifications/{id}/seen")
    public Map<String, Object> seen(@PathVariable String id, @RequestParam(defaultValue = "office") String source) {
        return facade.seenNotification(PrincipalContext.getPrincipalInfo(), source, id);
    }
    /** 标记全部通知已读。 */
    @PostMapping("/notifications/seen-all")
    public Map<String, Object> seenAll(@RequestParam(defaultValue = "office") String source) {
        return facade.seenAllNotifications(PrincipalContext.getPrincipalInfo(), source);
    }

    /** 计划详情；query {@code source} 可缺省为 office。 */
    @GetMapping("/task-plans/{id}")
    public Map<String, Object> plan(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return Map.of("plan", facade.planView(principal, id, source == null ? "office" : source));
    }

    /**
     * 计划状态操作（pause/resume 等）。请求字段：{@code operation}（或 {@code status}，必需字符串；
     * 非字符串 400 INVALID_PARAMETERS）、{@code confirmed}（可选布尔）、{@code version}
     * （必需 >=1 无损整数，非法 400 INVALID_VERSION）。
     */
    @PostMapping("/task-plans/{id}/status")
    public Map<String, Object> planStatus(@PathVariable String id, @RequestParam(defaultValue = "office") String source,
                                          @RequestBody Map<String, Object> body) {
        String operation = text(body, "operation", text(body, "status", ""));
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        return facade.updatePlan(PrincipalContext.getPrincipalInfo(), id, version(body), operation, confirmed, source);
    }
    /** 计划列表；query {@code source} 可缺省为 office。 */
    @GetMapping("/task-plans")
    public Map<String, Object> plans(@RequestParam(defaultValue = "office") String source) {
        return Map.of("plans", facade.plans(PrincipalContext.getPrincipalInfo(), source));
    }

    /**
     * 取消计划。请求字段：{@code scope}（occurrence 时 {@code date} 必须是合法日期字符串，
     * 非字符串/非法日期 400 INVALID_CANCELLATION）、{@code version}（必需 >=1 无损整数）。
     */
    @PostMapping("/task-plans/{id}/cancel")
    public Map<String, Object> cancelPlan(@PathVariable String id, @RequestParam(defaultValue = "office") String source,
                                          @RequestBody Map<String, Object> body) {
        String scope = text(body, "scope", "");
        LocalDate date = null;
        if ("occurrence".equals(scope)) {
            Object raw = body.get("date");
            if (raw != null && !(raw instanceof String value)) {
                throw new BusinessException("INVALID_CANCELLATION", "请选择有效取消日期", 400);
            }
            date = raw == null ? null : localDate((String) raw, "请选择有效取消日期", "INVALID_CANCELLATION");
        }
        return facade.cancelPlan(PrincipalContext.getPrincipalInfo(), source, id, version(body), scope, date);
    }

    private com.ehe.iotagent.task.TaskProjectionStore projection() {
        if (projections == null) throw new IllegalStateException("任务时间轴与日历存储未配置");
        return projections;
    }
    private static DateRange dateTimeRange(String from, String to) {
        Instant end = to == null || to.isBlank() ? Instant.now() : instant(to);
        Instant start = from == null || from.isBlank() ? end.minus(Duration.ofDays(31)) : instant(from);
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(Duration.ofDays(62)) > 0) {
            throw new BusinessException("INVALID_DATE_RANGE", "请选择62天以内的时间范围", 400);
        }
        return new DateRange(start, end);
    }
    private static Instant instant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException error) { throw new BusinessException("INVALID_DATE_RANGE", "请选择有效时间范围", 400); }
    }
    private static LocalDate localDate(String value, String message) {
        return localDate(value, message, "INVALID_DATE");
    }
    private static LocalDate localDate(String value, String message, String code) {
        if (value == null || value.isBlank()) throw new BusinessException(code, message, 400);
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (!parsed.toString().equals(value)) throw new DateTimeException("non-canonical");
            return parsed;
        } catch (DateTimeException error) {
            throw new BusinessException(code, message, 400);
        }
    }
    private static LocalDate localDateBoundary(String value, String message) {
        if (value != null && value.contains("T")) {
            try { return Instant.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
            catch (RuntimeException ignored) { }
        }
        return localDate(value, message);
    }
    private String json(Object value) {
        if (value == null) return "{}";
        if (value instanceof String text) return text;
        try { return mapper.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalArgumentException("任务内容无效"); }
    }
    /** 可缺省文本：仅键缺失用默认值；出现（含显式 null/数字）即 400 INVALID_PARAMETERS。 */
    private static String text(Map<String, Object> body, String key, String fallback) {
        return RequestValues.optionalText(body, key, fallback, "INVALID_PARAMETERS", key + " 无效", 400);
    }
    /** source：可缺省为 office；出现即必须是字符串（值合法性由 facade 判定 INVALID_AGENT_SOURCE）。 */
    private String source(Map<String, Object> body) {
        String source = RequestValues.optionalText(body, "source", "office", "INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        allowlist.requireCompanyTasks(PrincipalContext.getPrincipalInfo(), source);
        return source;
    }
    /** 必需版本：缺失/显式 null/小数（含 1.0）/字符串/溢出统一 400 INVALID_VERSION，绝不截断。 */
    private static long version(Map<String, Object> body) {
        return RequestValues.requiredLong(body, "version", 1, "INVALID_VERSION", "请刷新后重试", 400);
    }
    /** 可缺省版本：仅键缺失时用历史兼容默认 1；键存在即必须合法（>=1 无损整数）。 */
    private static long versionOrDefault(Map<String, Object> body) {
        Long version = RequestValues.optionalLong(body, "version", 1, "INVALID_VERSION", "请刷新后重试", 400);
        return version == null ? 1L : version;
    }
    private record DateRange(Instant from, Instant to) { }
}
