package ltd.qianc.iotagent.task.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskProposalFact;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runtime-compatible task, plan and inbox routes; all state moves stay in TaskFacade. */
@RestController
@RequestMapping("/api/agent")
public final class TaskController {
    private final TaskFacade facade;
    private final ObjectMapper mapper;
    private final ltd.qianc.iotagent.task.TaskProjectionStore projections;
    private final IotAdapter iot;
    public TaskController(TaskFacade facade, ObjectMapper mapper) { this(facade, mapper, null, null); }
    public TaskController(TaskFacade facade, ObjectMapper mapper, ltd.qianc.iotagent.task.TaskProjectionStore projections) {
        this(facade, mapper, projections, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TaskController(TaskFacade facade, ObjectMapper mapper, ltd.qianc.iotagent.task.TaskProjectionStore projections, IotAdapter iot) {
        this.facade = facade; this.mapper = mapper; this.projections = projections; this.iot = iot;
    }

    @PostMapping("/task-proposals")
    public Map<String, Object> propose(@RequestBody Map<String, Object> body) {
        String payload = json(body.get("payload"));
        TaskProposalFact proposal = facade.propose(PrincipalContext.getPrincipalInfo(), text(body, "source", "office"),
                text(body, "conversationId", ""), text(body, "turnId", ""), payload, text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }
    @GetMapping("/task-proposals/{id}")
    public Map<String, Object> proposal(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }
    @PostMapping("/task-proposals/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @RequestParam(required = false) String source,
                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.view(source == null ? facade.confirm(principal, id, version(body))
                : facade.confirm(principal, id, version(body), source));
    }
    @PostMapping("/task-proposals/{id}/discard")
    public Map<String, Object> discard(@PathVariable String id, @RequestParam(required = false) String source,
                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.discard(principal, id, version(body))
                : facade.discard(principal, id, version(body), source));
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/confirm")
    public Map<String, Object> confirmTask(@PathVariable String id, @RequestParam(required = false) String source,
                                           @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.view(source == null ? facade.confirm(principal, id, version(body))
                : facade.confirm(principal, id, version(body), source));
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/discard")
    public Map<String, Object> discardTask(@PathVariable String id, @RequestParam(required = false) String source,
                                           @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.discard(principal, id, version(body))
                : facade.discard(principal, id, version(body), source));
    }

    @GetMapping("/task-changes/{id}")
    public Map<String, Object> taskChange(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }
    @GetMapping("/task-cancellations/{id}")
    public Map<String, Object> taskCancellation(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.proposalView(source == null ? facade.proposal(principal, id) : facade.proposal(principal, id, source));
    }
    @PostMapping("/task-changes")
    public Map<String, Object> proposeTaskChange(@RequestBody Map<String, Object> body) {
        TaskProposalFact proposal = facade.proposeChange(PrincipalContext.getPrincipalInfo(), text(body, "source", "office"),
                text(body, "conversationId", ""), text(body, "turnId", ""), json(body), text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }
    @PostMapping("/task-changes/{id}/confirm")
    public Map<String, Object> confirmTaskChange(@PathVariable String id, @RequestParam(required = false) String source,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return source == null ? facade.confirmChange(principal, id, versionOrDefault(body))
                : facade.confirmChange(principal, id, versionOrDefault(body), source);
    }
    @PostMapping("/task-changes/{id}/discard")
    public Map<String, Object> discardTaskChange(@PathVariable String id, @RequestParam(required = false) String source,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        TaskProposalFact discarded = source == null ? facade.discardChange(principal, id, versionOrDefault(body))
                : facade.discardChange(principal, id, versionOrDefault(body), source);
        return facade.proposalView(discarded);
    }
    @PostMapping("/task-cancellations")
    public Map<String, Object> proposeTaskCancellation(@RequestBody Map<String, Object> body) {
        TaskProposalFact proposal = facade.proposeCancellation(PrincipalContext.getPrincipalInfo(), text(body, "source", "office"),
                text(body, "conversationId", ""), text(body, "turnId", ""), json(body), text(body, "idempotencyKey", ""));
        return facade.proposalView(proposal);
    }
    @PostMapping("/task-cancellations/{id}/confirm")
    public Map<String, Object> confirmTaskCancellation(@PathVariable String id, @RequestParam(required = false) String source,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return source == null ? facade.confirmCancellation(principal, id, versionOrDefault(body))
                : facade.confirmCancellation(principal, id, versionOrDefault(body), source);
    }
    @PostMapping("/task-cancellations/{id}/discard")
    public Map<String, Object> discardTaskCancellation(@PathVariable String id, @RequestParam(required = false) String source,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        TaskProposalFact discarded = source == null ? facade.discardCancellation(principal, id, versionOrDefault(body))
                : facade.discardCancellation(principal, id, versionOrDefault(body), source);
        return facade.proposalView(discarded);
    }


    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "office") String source,
                                     @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        DateRange range = dateTimeRange(from, to);
        return Map.of("tasks", projection().list(PrincipalContext.getPrincipalInfo(), source, range.from(), range.to()));
    }
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
    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        String requestedSource = source == null ? "office" : source;
        var task = facade.current(principal, id, requestedSource);
        return facade.view(principal, task, requestedSource);
    }
    @GetMapping("/tasks/{id}/lighting-states")
    public Map<String, Object> lightingStates(@PathVariable String id,
                                               @RequestParam(defaultValue = "office") String source) {
        return facade.lightingStates(PrincipalContext.getPrincipalInfo(), id, source);
    }

    @PostMapping("/tasks/{id}/update")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return facade.view(facade.update(PrincipalContext.getPrincipalInfo(), id, version(body), json(body.get("payload"))));
    }
    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestParam(required = false) String source,
                                      @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.view(source == null ? facade.cancel(principal, id, version(body))
                : facade.cancel(principal, id, version(body), source));
    }

    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestParam(defaultValue = "office") String source,
                                             @RequestParam(defaultValue = "30") int limit,
                                             @RequestParam(required = false) String before) {
        var items = facade.notifications(PrincipalContext.getPrincipalInfo(), source, limit, before);
        return Map.of("notifications", items, "unreadCount", facade.unreadNotificationCount(PrincipalContext.getPrincipalInfo(), source));
    }
    @PostMapping("/notifications/{id}/seen")
    public Map<String, Object> seen(@PathVariable String id, @RequestParam(defaultValue = "office") String source) {
        return facade.seenNotification(PrincipalContext.getPrincipalInfo(), source, id);
    }
    @PostMapping("/notifications/seen-all")
    public Map<String, Object> seenAll(@RequestParam(defaultValue = "office") String source) {
        return facade.seenAllNotifications(PrincipalContext.getPrincipalInfo(), source);
    }

    @PostMapping("/task-plans")
    public Map<String, Object> createPlan(@RequestBody Map<String, Object> body) {
        TaskPlanFact plan = facade.createPlan(PrincipalContext.getPrincipalInfo(), text(body, "source", "office"),
                json(body.get("payload")), text(body, "idempotencyKey", ""));
        return facade.planView(plan);
    }
    @GetMapping("/task-plans/{id}")
    public Map<String, Object> plan(@PathVariable String id, @RequestParam(required = false) String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        return facade.planView(source == null ? facade.plan(principal, id) : facade.plan(principal, id, source));
    }
    @PostMapping("/task-plans/{id}/status")
    public Map<String, Object> planStatus(@PathVariable String id, @RequestParam(defaultValue = "office") String source,
                                          @RequestBody Map<String, Object> body) {
        String status = body.get("status") instanceof String ? text(body, "status", "") : text(body, "operation", "");
        boolean confirmed = Boolean.TRUE.equals(body.get("confirmed"));
        return facade.planView(facade.updatePlan(PrincipalContext.getPrincipalInfo(), id, version(body),
                status, body.get("payload") == null ? null : json(body.get("payload")), confirmed, source));
    }
    @GetMapping("/task-plans")
    public Map<String, Object> plans(@RequestParam(defaultValue = "office") String source) {
        return Map.of("plans", facade.plans(PrincipalContext.getPrincipalInfo(), source));
    }
    @PostMapping("/task-plans/{id}/cancel")
    public Map<String, Object> cancelPlan(@PathVariable String id, @RequestParam(defaultValue = "office") String source,
                                          @RequestBody Map<String, Object> body) {
        String scope = text(body, "scope", "");
        LocalDate date = "occurrence".equals(scope) && body.get("date") instanceof String value
                ? localDate(value, "请选择有效取消日期", "INVALID_CANCELLATION") : null;
        return facade.cancelPlan(PrincipalContext.getPrincipalInfo(), source, id, version(body), scope, date);
    }

    private ltd.qianc.iotagent.task.TaskProjectionStore projection() {
        if (projections == null) throw new IllegalStateException("任务时间轴与日历存储未配置");
        return projections;
    }
    private static DateRange dateTimeRange(String from, String to) {
        Instant start = instant(from), end = instant(to);
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
    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body == null ? null : body.get(key); return value instanceof String string ? string : fallback;
    }
    private static long version(Map<String, Object> body) {
        Object value = body == null ? null : body.get("version");
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw new BusinessException("INVALID_VERSION", "请刷新后重试", 400);
        }
        return number.longValue();
    }
    private static long versionOrDefault(Map<String, Object> body) {
        Object value = body == null ? null : body.get("version");
        return value instanceof Number number && number.longValue() >= 1 ? number.longValue() : 1L;
    }
    private static Map<String, Object> stateView(String id, Object raw) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        if (raw instanceof Map<?, ?> values) {
            values.forEach((key, value) -> { if (key instanceof String name) view.put(name, value); });
        } else {
            view.put("state", raw);
        }
        view.putIfAbsent("state", null);
        view.putIfAbsent("reportedAt", null);
        return view;
    }
    private static IotSource parseSource(String value) {
        try { return IotSource.parse(value == null ? "office" : value); }
        catch (RuntimeException error) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400); }
    }
    private record DateRange(Instant from, Instant to) { }
}
