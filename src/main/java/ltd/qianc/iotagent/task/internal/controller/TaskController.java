package ltd.qianc.iotagent.task.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
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
    public Map<String, Object> proposal(@PathVariable String id) { return facade.proposalView(facade.proposal(PrincipalContext.getPrincipalInfo(), id)); }
    @PostMapping("/task-proposals/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.confirm(PrincipalContext.getPrincipalInfo(), id, version(body)));
    }
    @PostMapping("/task-proposals/{id}/discard")
    public Map<String, Object> discard(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.proposalView(facade.discard(PrincipalContext.getPrincipalInfo(), id, version(body)));
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/confirm")
    public Map<String, Object> confirmTask(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.confirm(PrincipalContext.getPrincipalInfo(), id, version(body)));
    }
    /** Runtime alias for clients that identify the pending proposal as a task. */
    @PostMapping("/tasks/{id}/discard")
    public Map<String, Object> discardTask(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.proposalView(facade.discard(PrincipalContext.getPrincipalInfo(), id, version(body)));
    }

    @PostMapping("/task-changes/{id}/confirm")
    public Map<String, Object> confirmTaskChange(@PathVariable String id) {
        throw unsupportedTaskChange();
    }
    @PostMapping("/task-changes/{id}/discard")
    public Map<String, Object> discardTaskChange(@PathVariable String id) {
        throw unsupportedTaskChange();
    }
    @PostMapping("/task-cancellations/{id}/confirm")
    public Map<String, Object> confirmTaskCancellation(@PathVariable String id) {
        throw unsupportedTaskCancellation();
    }
    @PostMapping("/task-cancellations/{id}/discard")
    public Map<String, Object> discardTaskCancellation(@PathVariable String id) {
        throw unsupportedTaskCancellation();
    }


    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "office") String source,
                                     @RequestParam String from, @RequestParam String to) {
        return Map.of("tasks", projection().list(PrincipalContext.getPrincipalInfo(), source, instant(from), instant(to)));
    }
    @GetMapping("/regions/{regionId}/timeline")
    public Map<String, Object> regionTimeline(@PathVariable String regionId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        return Map.of("tasks", projection().regionTimeline(PrincipalContext.getPrincipalInfo(), source, regionId, instant(from), instant(to)));
    }
    @GetMapping("/regions/{regionId}/calendar")
    public Map<String, Object> regionCalendar(@PathVariable String regionId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        return Map.of("days", projection().regionCalendar(PrincipalContext.getPrincipalInfo(), source, regionId, instant(from), instant(to)));
    }
    @GetMapping("/devices/{deviceId}/timeline")
    public Map<String, Object> deviceTimeline(@PathVariable String deviceId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        return Map.of("events", projection().deviceTimeline(PrincipalContext.getPrincipalInfo(), source, deviceId, instant(from), instant(to)));
    }
    @GetMapping("/devices/{deviceId}/calendar")
    public Map<String, Object> deviceCalendar(@PathVariable String deviceId, @RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "office") String source) {
        return Map.of("days", projection().deviceCalendar(PrincipalContext.getPrincipalInfo(), source, deviceId, instant(from), instant(to)));
    }
    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable String id) { return facade.view(facade.current(PrincipalContext.getPrincipalInfo(), id)); }
    @GetMapping("/tasks/{id}/lighting-states")
    public Map<String, Object> lightingStates(@PathVariable String id,
                                               @RequestParam(defaultValue = "office") String source) {
        if (iot == null) throw new UpstreamUnavailableException("物联设备状态适配器未接入");
        try {
            var principal = PrincipalContext.getPrincipalInfo();
            IotSource requestedSource = parseSource(source);
            List<String> deviceIds = facade.lightingDeviceIds(principal, id, requestedSource.wireValue());
            RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), requestedSource);
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(30));
            if (scope == null || scope.source() != requestedSource) throw new UpstreamUnavailableException("读取任务设备授权范围");
            for (String deviceId : deviceIds) {
                var authorized = scope.device(deviceId);
                if (authorized.isEmpty() || !"lighting".equalsIgnoreCase(authorized.get().kind())) {
                    throw new BusinessException("TASK_NOT_FOUND", "任务不存在", 404);
                }
            }
            DeviceStateReadResult result = deviceIds.isEmpty()
                    ? new DeviceStateReadResult(Map.of(), Instant.now().toString())
                    : iot.readDeviceStates(identity, deviceIds).block(Duration.ofSeconds(30));
            if (result == null) throw new UpstreamUnavailableException("读取设备状态");
            List<Map<String, Object>> states = new ArrayList<>();
            for (String deviceId : deviceIds) states.add(stateView(deviceId, result.states().get(deviceId)));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("observedAt", result.observedAt());
            response.put("states", states);
            return response;
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new UpstreamUnavailableException("读取任务设备状态");
        }
    }

    @PostMapping("/tasks/{id}/update")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return facade.view(facade.update(PrincipalContext.getPrincipalInfo(), id, version(body), json(body.get("payload"))));
    }
    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.cancel(PrincipalContext.getPrincipalInfo(), id, version(body)));
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
    public Map<String, Object> plan(@PathVariable String id) { return facade.planView(facade.plan(PrincipalContext.getPrincipalInfo(), id)); }
    @PostMapping("/task-plans/{id}/status")
    public Map<String, Object> planStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String status = body.get("status") instanceof String ? text(body, "status", "") : text(body, "operation", "");
        return facade.planView(facade.updatePlan(PrincipalContext.getPrincipalInfo(), id, version(body),
                status, body.get("payload") == null ? null : json(body.get("payload"))));
    }
    @GetMapping("/task-plans")
    public Map<String, Object> plans(@RequestParam(defaultValue = "office") String source) {
        return Map.of("plans", facade.plans(PrincipalContext.getPrincipalInfo(), source));
    }
    @PostMapping("/task-plans/{id}/cancel")
    public Map<String, Object> cancelPlan(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return facade.planView(facade.updatePlan(PrincipalContext.getPrincipalInfo(), id, version(body), "DELETED", null));
    }

    private ltd.qianc.iotagent.task.TaskProjectionStore projection() {
        if (projections == null) throw new IllegalStateException("任务时间轴与日历存储未配置");
        return projections;
    }
    private static java.time.Instant instant(String value) {
        try { return java.time.Instant.parse(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("请选择有效时间范围"); }
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
        if (!(value instanceof Number number) || number.longValue() < 1) throw new IllegalArgumentException("version is required");
        return number.longValue();
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
    private static BusinessException unsupportedTaskChange() {
        return new BusinessException("TASK_CHANGE_UNAVAILABLE", "任务修改暂未接入，未执行任何变更", 503);
    }
    private static BusinessException unsupportedTaskCancellation() {
        return new BusinessException("TASK_CANCELLATION_UNAVAILABLE", "任务取消提案暂未接入，未执行任何变更", 503);
    }
}
