package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;

/**
 * Public task boundary. It performs proposal/plan/task CAS and idempotency while
 * execution, scheduling and durable facts stay behind injected adapters.
 */
public final class TaskFacade {
    private static final String PENDING = "PENDING_CONFIRMATION";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final String SCHEDULED = "SCHEDULED";
    private final ObjectMapper mapper;
    private final TaskFactStore tasks;
    private final TaskProposalStore proposals;
    private final TaskPlanStore plans;
    private final TaskNotificationStore notifications;
    private final TaskScheduler scheduler;
    private final Clock clock;

    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TaskProposalFact propose(PrincipalInfo principal, String source, String conversationId,
                                    String turnId, String payloadJson, String idempotencyKey) {
        checkPrincipal(principal); String normalizedSource = source(source);
        require(conversationId, "conversationId"); require(turnId, "turnId"); require(idempotencyKey, "idempotencyKey");
        String key = scoped(principal, idempotencyKey);
        Optional<TaskProposalFact> prior = proposals.findByIdempotencyKey(key);
        if (prior.isPresent() && owns(prior.get().payloadJson(), principal)) return prior.get();
        JsonNode payload = safePayload(payloadJson);
        if (payload.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("source", normalizedSource)
                    .put("tenantCode", principal.tenantCode()).put("ownerKey", ownerKey(principal))
                    .put("conversationId", conversationId).put("turnId", turnId)
                    .put("createdAt", clock.instant().toString());
        }
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), 1, PENDING, payload.toString(), key);
        try { return proposals.save(created); }
        catch (RuntimeException error) {
            return proposals.findByIdempotencyKey(key).orElseThrow(() -> error);
        }
    }

    public TaskProposalFact proposal(PrincipalInfo principal, String proposalId) {
        TaskProposalFact proposal = proposals.find(proposalId).orElseThrow(() -> notFound("TASK_PROPOSAL_NOT_FOUND", "任务提案不存在"));
        if (!owns(proposal.payloadJson(), principal)) throw notFound("TASK_PROPOSAL_NOT_FOUND", "任务提案不存在");
        return proposal;
    }

    public TaskFact confirm(PrincipalInfo principal, String proposalId, long expectedVersion) {
        TaskProposalFact proposal = proposal(principal, proposalId);
        if (CONFIRMED.equals(proposal.status())) {
            TaskFact existing = taskForProposal(principal, proposalId);
            scheduleIfDue(existing);
            return existing;
        }
        if (!PENDING.equals(proposal.status())) throw conflict("TASK_PROPOSAL_NOT_PENDING", "任务提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        TaskProposalFact confirmed = proposals.updateIfVersion(
                new TaskProposalFact(proposal.proposalId(), proposal.version() + 1, CONFIRMED,
                        proposal.payloadJson(), proposal.idempotencyKey()), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
        String taskKey = "task:" + confirmed.proposalId();
        String taskPayload = materializePhaseIds(confirmed.payloadJson());
        TaskFact task = new TaskFact(UUID.randomUUID().toString(), 1, SCHEDULED,
                taskPayload, taskKey);
        TaskFact saved;
        try { saved = tasks.save(task); }
        catch (RuntimeException error) { saved = tasks.findByIdempotencyKey(taskKey).orElseThrow(() -> error); }
        scheduleIfDue(saved);
        return saved;
    }

    public TaskProposalFact discard(PrincipalInfo principal, String proposalId, long expectedVersion) {
        TaskProposalFact proposal = proposal(principal, proposalId);
        if (CANCELLED.equals(proposal.status())) return proposal;
        if (!PENDING.equals(proposal.status()) || proposal.version() != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        }
        return proposals.updateIfVersion(new TaskProposalFact(proposal.proposalId(), proposal.version() + 1,
                CANCELLED, proposal.payloadJson(), proposal.idempotencyKey()), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
    }

    public TaskFact current(PrincipalInfo principal, String taskId) {
        TaskFact task = tasks.find(taskId).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        if (!owns(task.payloadJson(), principal)) throw notFound("TASK_NOT_FOUND", "任务不存在");
        return task;
    }
    /**
     * Returns the durable lighting device identities for a task. The controller
     * rechecks those identities against the current gateway scope before reading
     * any state; malformed legacy payloads fail closed instead of being guessed.
     */
    public List<String> lightingDeviceIds(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        TaskFact task = current(principal, taskId);
        JsonNode payload = safePayload(task.payloadJson());
        String taskSource = source(payload.path("source").asText("office"));
        if (!taskSource.equals(source(requestedSource))) throw notFound("TASK_NOT_FOUND", "任务不存在");
        JsonNode phases = payload.path("phases");
        if (!phases.isArray()) {
            throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "任务缺少可读取的照明设备事实", 503);
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (JsonNode phase : phases) {
            JsonNode actions = phase.path("actions");
            if (!actions.isArray()) continue;
            for (JsonNode action : actions) {
                if (!"lighting".equalsIgnoreCase(action.path("kind").asText())) continue;
                String deviceId = action.path("deviceId").asText("");
                if (deviceId.isBlank()) {
                    throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "任务缺少可读取的照明设备事实", 503);
                }
                ids.add(deviceId);
            }
        }
        return List.copyOf(ids);
    }


    public TaskFact update(PrincipalInfo principal, String taskId, long expectedVersion, String payloadJson) {
        TaskFact current = current(principal, taskId);
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试");
        if (terminalTask(current.status())) throw conflict("TASK_ALREADY_FINISHED", "任务已结束");
        JsonNode nextPayload = safePayload(payloadJson);
        if (!nextPayload.isObject()) throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效", 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextPayload)
                .put("tenantCode", principal.tenantCode())
                .put("ownerKey", ownerKey(principal))
                .put("source", source(safePayload(current.payloadJson()).path("source").asText("office")));
        String normalizedPayload = materializePhaseIds(nextPayload.toString());
        TaskFact replacement = new TaskFact(taskId, expectedVersion + 1, SCHEDULED,
                normalizedPayload, current.idempotencyKey());
        TaskFact updated = tasks.updateIfVersion(replacement, expectedVersion)
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试"));
        cancelScheduledPhases(current);
        scheduleIfDue(updated);
        return updated;
    }

    public TaskFact cancel(PrincipalInfo principal, String taskId, long expectedVersion) {
        TaskFact current = current(principal, taskId);
        if (CANCELLED.equals(current.status())) return current;
        if (terminalTask(current.status())) throw conflict("TASK_ALREADY_FINISHED", "任务已结束");
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试");
        TaskFact cancelled = new TaskFact(taskId, expectedVersion + 1, CANCELLED,
                current.payloadJson(), current.idempotencyKey());
        TaskFact result = tasks.updateIfVersion(cancelled, expectedVersion)
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试"));
        cancelScheduledPhases(result);
        return result;
    }

    public TaskPlanFact createPlan(PrincipalInfo principal, String source, String payloadJson, String idempotencyKey) {
        checkPrincipal(principal); require(idempotencyKey, "idempotencyKey");
        String key = scoped(principal, idempotencyKey);
        Optional<TaskPlanFact> prior = plans.findByIdempotencyKey(key);
        if (prior.isPresent() && owns(prior.get().payloadJson(), principal)) return prior.get();
        JsonNode payload = safePayload(payloadJson);
        if (payload.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                .put("source", source(source)).put("tenantCode", principal.tenantCode()).put("ownerKey", ownerKey(principal));
        return plans.save(new TaskPlanFact(UUID.randomUUID().toString(), 1, "ENABLED", payload.toString(), key));
    }

    public TaskPlanFact plan(PrincipalInfo principal, String planId) {
        TaskPlanFact plan = plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在"));
        if (!owns(plan.payloadJson(), principal)) throw notFound("PLAN_NOT_FOUND", "计划不存在");
        return plan;
    }

    public TaskPlanFact updatePlan(PrincipalInfo principal, String planId, long expectedVersion, String status, String payloadJson) {
        TaskPlanFact current = plan(principal, planId);
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "计划已变化，请刷新后重试");
        String requested = status == null ? "" : status.trim();
        String next = switch (requested.toLowerCase()) {
            case "pause", "paused" -> "PAUSED";
            case "resume", "enabled", "enable" -> "ENABLED";
            case "delete", "deleted", "cancel", "cancelled" -> "DELETED";
            case "" -> current.status();
            default -> requested;
        };
        if (!(next.equals("ENABLED") || next.equals("PAUSED") || next.equals("DELETED"))) {
            throw new BusinessException("INVALID_PLAN_STATUS", "计划状态无效");
        }
        JsonNode nextPayload = payloadJson == null ? safePayload(current.payloadJson()) : safePayload(payloadJson);
        if (!nextPayload.isObject()) throw new BusinessException("INVALID_TASK_PAYLOAD", "计划内容无效", 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextPayload)
                .put("tenantCode", principal.tenantCode())
                .put("ownerKey", ownerKey(principal))
                .put("source", source(safePayload(current.payloadJson()).path("source").asText("office")));
        return plans.updateIfVersion(new TaskPlanFact(planId, expectedVersion + 1, next,
                nextPayload.toString(), current.idempotencyKey()), expectedVersion)
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请刷新后重试"));
    }

    public List<Map<String, Object>> notifications(PrincipalInfo principal, String source, int limit, String before) {
        if (limit < 1 || limit > 100) throw new BusinessException("INVALID_LIMIT", "请选择有效分页大小");
        return notifications.list(principal.tenantCode(), ownerKey(principal), source(source), limit, before)
                .stream().map(this::notificationView).toList();
    }
    public long unreadNotificationCount(PrincipalInfo principal, String source) {
        return notifications.unreadCount(principal.tenantCode(), ownerKey(principal), source(source));
    }


    public Map<String, Object> seenNotification(PrincipalInfo principal, String source, String id) {
        TaskNotificationFact fact = notifications.markSeen(principal.tenantCode(), ownerKey(principal), source(source), id, clock.instant());
        return Map.of("id", fact.notificationId(), "readAt", fact.readAt() == null ? clock.instant().toString() : fact.readAt().toString());
    }

    public Map<String, Object> seenAllNotifications(PrincipalInfo principal, String source) {
        Instant at = clock.instant();
        return Map.of("readAt", at.toString(), "marked", notifications.markAllSeen(principal.tenantCode(), ownerKey(principal), source(source), at));
    }

    public Map<String, Object> view(TaskFact task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", task.taskId()); view.put("version", task.version()); view.put("status", task.status());
        view.put("payload", mapper.convertValue(safePayload(task.payloadJson()), Object.class));
        return view;
    }
    public List<Map<String, Object>> plans(PrincipalInfo principal, String source) {
        return plans.list(principal.tenantCode(), ownerKey(principal), source(source)).stream()
                .filter(item -> owns(item.payloadJson(), principal)).map(this::planView).toList();
    }
    public Map<String, Object> proposalView(TaskProposalFact proposal) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("proposalId", proposal.proposalId()); view.put("version", proposal.version()); view.put("status", proposal.status());
        view.put("payload", mapper.convertValue(safePayload(proposal.payloadJson()), Object.class));
        return view;
    }
    public Map<String, Object> planView(TaskPlanFact plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", plan.planId()); view.put("version", plan.version()); view.put("status", plan.status());
        view.put("payload", mapper.convertValue(safePayload(plan.payloadJson()), Object.class));
        return view;
    }

    private TaskFact taskForProposal(PrincipalInfo principal, String proposalId) {
        return tasks.findByIdempotencyKey("task:" + proposalId)
                .orElseThrow(() -> new BusinessException("TASK_RECOVERY_REQUIRED", "任务确认已记账，正在恢复任务事实", 503));
    }
    private String materializePhaseIds(String payloadJson) {
        JsonNode parsed = safePayload(payloadJson);
        if (!parsed.isObject() || !parsed.path("phases").isArray()) return parsed.toString();
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        for (JsonNode item : root.withArray("phases")) {
            if (item.isObject()) {
                com.fasterxml.jackson.databind.node.ObjectNode phase = (com.fasterxml.jackson.databind.node.ObjectNode) item;
                if (!phase.path("phaseId").isTextual() || phase.path("phaseId").asText().isBlank()) {
                    phase.put("phaseId", UUID.randomUUID().toString());
                }
            }
        }
        return root.toString();
    }

    private void scheduleIfDue(TaskFact task) {
        JsonNode payload = safePayload(task.payloadJson());
        JsonNode phases = payload.path("phases");
        if (!phases.isArray()) return;
        for (JsonNode phase : phases) {
            if (phase.path("phaseId").isTextual() && phase.path("at").isTextual()) {
                try { scheduler.schedule(task.taskId(), phase.path("phaseId").asText(), Instant.parse(phase.path("at").asText())); }
                catch (RuntimeException error) {
                    throw new BusinessException("TASK_SCHEDULER_UNAVAILABLE", "任务已记账，但调度服务暂不可用，请稍后重试", 503);
                }
            }
        }
    }
    private static boolean terminalTask(String status) {
        return Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED").contains(status);
    }
    private void cancelScheduledPhases(TaskFact task) {
        JsonNode phases = safePayload(task.payloadJson()).path("phases");
        if (phases.isArray()) {
            for (JsonNode phase : phases) {
                if (phase.path("phaseId").isTextual()) {
                    try { scheduler.cancel(task.taskId(), phase.path("phaseId").asText()); } catch (RuntimeException ignored) { }
                }
            }
        } else {
            try { scheduler.cancel(task.taskId(), task.taskId()); } catch (RuntimeException ignored) { }
        }
    }
    private JsonNode safePayload(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        return TaskPayloadSafety.withoutCredentials(mapper, readJson(value));
    }
    private JsonNode readJson(String value) { try { return mapper.readTree(value); } catch (Exception error) { throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效"); } }
    private boolean owns(String payload, PrincipalInfo principal) { return ownerKey(principal).equals(readJson(payload).path("ownerKey").asText()); }
    private Map<String, Object> notificationView(TaskNotificationFact fact) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", fact.notificationId());
        view.put("taskId", fact.taskId());
        view.put("source", fact.source());
        view.put("outcome", fact.outcome());
        view.put("endedAt", fact.endedAt().toString());
        view.put("readAt", fact.readAt() == null ? null : fact.readAt().toString());
        view.put("payload", mapper.convertValue(safePayload(fact.payloadJson()), Object.class));
        return view;
    }
    private static void checkPrincipal(PrincipalInfo principal) {
        if (principal == null || principal.authorization() == null || principal.authorization().isBlank()
                || principal.tenantCode() == null || principal.tenantCode().isBlank()) {
            throw new BusinessException("UNAUTHORIZED", "缺少登录凭据或园区标识，请重新登录", 401);
        }
    }

    private static String source(String value) {
        String normalized = value == null || value.isBlank() ? "office" : value;
        if (!normalized.equals("office") && !normalized.equals("company")) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
        return normalized;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException("INVALID_TASK_REQUEST", field + " 不能为空", 400);
    }

    private static BusinessException notFound(String code, String message) {
        return new BusinessException(code, message, 404);
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(code, message, 409);
    }
    private static String scoped(PrincipalInfo principal, String key) {
        return principal.tenantCode() + "\u0000" + ownerKey(principal) + "\u0000" + key;
    }
    public static String ownerKey(PrincipalInfo principal) {
        return principal.ownerKey();
    }
}
