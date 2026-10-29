package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
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
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
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
    private final TaskPhaseStore phases;
    private final IotAdapter iot;
    private final Clock clock;

    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, null, null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, phases, null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.phases = phases;
        this.iot = iot;
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
        Instant expiresAt = clock.instant().plus(java.time.Duration.ofMinutes(15));
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), 1, PENDING, payload.toString(), key,
                "TASK", "{}", "{}", payload.toString(), "create", principal.tenantCode(), ownerKey(principal),
                normalizedSource, expiresAt);
        try { return proposals.save(created); }
        catch (RuntimeException error) {
            return proposals.findByIdempotencyKey(key).orElseThrow(() -> error);
        }
    }

    /** Creates a durable Runtime-compatible edit/plan-management proposal. */
    public TaskProposalFact proposeChange(PrincipalInfo principal, String source, String conversationId, String turnId,
                                          String payloadJson, String idempotencyKey) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        require(conversationId, "conversationId"); require(turnId, "turnId"); require(idempotencyKey, "idempotencyKey");
        JsonNode request = safePayload(payloadJson);
        JsonNode target = target(request);
        String operation = request.path("operation").asText("").trim().toLowerCase();
        if (!Set.of("edit", "pause", "resume", "delete", "skip").contains(operation)) {
            throw new BusinessException("INVALID_CHANGE", "任务修改操作无效", 400);
        }
        String id = targetId(target);
        TaskRecurrence.OccurrenceReference occurrence = occurrenceTarget(target);
        boolean plan = "task_plan".equals(target.path("type").asText()) || "plan".equals(target.path("type").asText())
                || occurrence != null;
        if ((target.has("occurrence") || target.has("occurrenceDate") || target.has("date")) && occurrence == null) {
            throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "本次发生身份无效，拒绝修改本次发生", 409);
        }
        if (occurrence != null && !Set.of("edit", "skip").contains(operation)) {
            throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "本次发生仅支持修改或跳过", 409);
        }
        if (plan && "skip".equals(operation) && occurrence == null) {
            throw new BusinessException("TASK_OCCURRENCE_REQUIRED", "跳过计划必须明确 occurrence 身份", 409);
        }
        String planId = occurrence == null ? id : occurrence.planId();
        JsonNode before = occurrence != null ? occurrenceForProposal(principal, normalizedSource, occurrence)
                : plan ? planForProposal(principal, normalizedSource, planId) : taskForProposal(principal, normalizedSource, id);
        long targetVersion = plan ? plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在")).version()
                : tasks.find(id).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在")).version();
        JsonNode after = changedPayload(before, request, operation);
        if ("edit".equals(operation)) {
            validatePlanStructure(after, before.toString());
            if (plan && occurrence == null && before.has("repeat") && !after.has("repeat")) {
                throw new BusinessException("INVALID_CHANGE", "周期计划必须保留重复规则", 400);
            }
            validateFutureChange(before, after, operation);
            validateDurableHistory(before, after);
        }
        validateDeviceReferences(before, after);
        String key = scoped(principal, idempotencyKey);
        Optional<TaskProposalFact> prior = proposals.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            if (!proposalOwned(prior.get(), principal, normalizedSource)) throw notFound("TASK_CHANGE_NOT_FOUND", "任务修改提案不存在");
            return prior.get();
        }
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), targetVersion, PENDING, request.toString(), key,
                "CHANGE", target.toString(), before.toString(), after.toString(), operation, principal.tenantCode(),
                ownerKey(principal), normalizedSource, clock.instant().plus(java.time.Duration.ofMinutes(15)));
        try { return proposals.save(created); }
        catch (RuntimeException error) { return proposals.findByIdempotencyKey(key).orElseThrow(() -> error); }
    }

    /** Creates a durable cancellation proposal without mutating a task. */
    public TaskProposalFact proposeCancellation(PrincipalInfo principal, String source, String conversationId, String turnId,
                                                 String payloadJson, String idempotencyKey) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        require(conversationId, "conversationId"); require(turnId, "turnId"); require(idempotencyKey, "idempotencyKey");
        JsonNode request = safePayload(payloadJson);
        JsonNode target = target(request);
        if (!"task".equals(target.path("type").asText())) {
            throw new BusinessException("TASK_CANCELLATION_UNSUPPORTED", "当前仅支持一次性任务取消", 409);
        }
        String taskId = targetId(target);
        TaskFact current = current(principal, taskId);
        assertSource(current.payloadJson(), normalizedSource);
        String key = scoped(principal, idempotencyKey);
        Optional<TaskProposalFact> prior = proposals.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            if (!proposalOwned(prior.get(), principal, normalizedSource)) throw notFound("TASK_CANCELLATION_NOT_FOUND", "任务取消提案不存在");
            return prior.get();
        }
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), current.version(), PENDING, request.toString(), key,
                "CANCELLATION", target.toString(), current.payloadJson(), current.payloadJson(), "cancel",
                principal.tenantCode(), ownerKey(principal), normalizedSource, clock.instant().plus(java.time.Duration.ofMinutes(15)));
        try { return proposals.save(created); }
        catch (RuntimeException error) { return proposals.findByIdempotencyKey(key).orElseThrow(() -> error); }
    }

    public Map<String, Object> confirmChange(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return confirmChange(principal, proposalId, expectedVersion, "office");
    }

    public Map<String, Object> confirmChange(PrincipalInfo principal, String proposalId, long expectedVersion,
                                             String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskProposalFact proposal = managementProposal(principal, proposalId, "CHANGE", normalizedSource);
        if (CONFIRMED.equals(proposal.status())) return managementResult(proposal);
        resolveExpiry(proposal);
        if (!PENDING.equals(proposal.status())) throw conflict("CHANGE_NOT_PENDING", "修改提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出修改");
        JsonNode target = readJson(proposal.targetJson());
        assertCurrentCatalog(principal, proposal.source(), proposal.afterJson(),
                "resume".equals(proposal.operation()));
        if ("edit".equals(proposal.operation())) {
            validatePlanStructure(readJson(proposal.afterJson()), proposal.beforeJson());
            validateFutureChange(readJson(proposal.beforeJson()), readJson(proposal.afterJson()), proposal.operation());
            validateDurableHistory(readJson(proposal.beforeJson()), readJson(proposal.afterJson()));
        }
        validateDeviceReferences(readJson(proposal.beforeJson()), readJson(proposal.afterJson()));
        String id = targetId(target);
        TaskRecurrence.OccurrenceReference occurrence = occurrenceTarget(target);
        boolean plan = "task_plan".equals(target.path("type").asText()) || "plan".equals(target.path("type").asText())
                || occurrence != null;
        String planId = occurrence == null ? id : occurrence.planId();
        if (plan) {
            TaskPlanFact current = plan(principal, planId, normalizedSource);
            if (current.version() != proposal.version()) throw conflict("VERSION_CONFLICT", "计划已变化，请重新提出修改");
            if (occurrence != null) {
                String override = "skip".equals(proposal.operation()) ? null : safePayload(proposal.afterJson()).toString();
                TaskPlanOccurrence changed;
                try {
                    changed = plans.overrideOccurrenceIfVersion(planId, occurrence.date(), current.version(), override, clock.instant())
                            .orElseThrow(() -> conflict("VERSION_CONFLICT", "本次发生已变化或已开始，请刷新后重试"));
                } catch (UnsupportedOperationException unavailable) {
                    throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生修改能力", 409);
                }
                markConfirmed(proposal);
                return managementResult(proposal, null, changed.planId());
            }
            String nextStatus = switch (proposal.operation()) {
                case "pause" -> "PAUSED";
                case "resume" -> "ENABLED";
                case "delete" -> "DELETED";
                default -> current.status();
            };
            TaskPlanFact updated = plans.updateIfVersion(new TaskPlanFact(planId, current.version() + 1, nextStatus,
                    safePayload(proposal.afterJson()).toString(), current.idempotencyKey()), current.version())
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请重新提出修改"));
            markConfirmed(proposal);
            return managementResult(proposal, null, updated.planId());
        }
        TaskFact current = current(principal, id, normalizedSource);
        if (current.version() != proposal.version()) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出修改");
        if (terminalTask(current.status())) throw conflict("TASK_NOT_EDITABLE", "已结束任务不可修改");
        String status = switch (proposal.operation()) {
            case "pause" -> "PAUSED";
            case "resume" -> SCHEDULED;
            case "delete" -> CANCELLED;
            default -> current.status().equals("PAUSED") ? "PAUSED" : SCHEDULED;
        };
        TaskFact updated = tasks.updateIfVersion(new TaskFact(id, current.version() + 1, status,
                materializePreservingPhaseIds(proposal.afterJson(), current.payloadJson()), current.idempotencyKey()), current.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务已变化，请重新提出修改"));
        if ("delete".equals(proposal.operation()) || "skip".equals(proposal.operation())) cancelScheduledPhases(current);
        if (!"pause".equals(proposal.operation()) && !"delete".equals(proposal.operation())) scheduleIfDue(updated);
        markConfirmed(proposal);
        return managementResult(proposal, id, null);
    }

    public TaskProposalFact discardChange(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return discardChange(principal, proposalId, expectedVersion, "office");
    }

    public TaskProposalFact discardChange(PrincipalInfo principal, String proposalId, long expectedVersion,
                                          String requestedSource) {
        return resolveManagement(principal, proposalId, expectedVersion, "CHANGE", source(requestedSource));
    }

    public Map<String, Object> confirmCancellation(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return confirmCancellation(principal, proposalId, expectedVersion, "office");
    }

    public Map<String, Object> confirmCancellation(PrincipalInfo principal, String proposalId, long expectedVersion,
                                                   String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskProposalFact proposal = managementProposal(principal, proposalId, "CANCELLATION", normalizedSource);
        if (CONFIRMED.equals(proposal.status())) return managementResult(proposal);
        resolveExpiry(proposal);
        if (!PENDING.equals(proposal.status())) throw conflict("CANCELLATION_NOT_PENDING", "取消提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出取消");
        JsonNode target = readJson(proposal.targetJson());
        TaskFact current = current(principal, targetId(target), normalizedSource);
        assertCurrentCatalog(principal, proposal.source(), current.payloadJson());
        if (current.version() != proposal.version()) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出取消");
        TaskFact cancelled = cancel(principal, current.taskId(), current.version(), normalizedSource);
        markConfirmed(proposal);
        return managementResult(proposal, cancelled.taskId(), null);
    }

    public TaskProposalFact discardCancellation(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return discardCancellation(principal, proposalId, expectedVersion, "office");
    }

    public TaskProposalFact discardCancellation(PrincipalInfo principal, String proposalId, long expectedVersion,
                                                 String requestedSource) {
        return resolveManagement(principal, proposalId, expectedVersion, "CANCELLATION", source(requestedSource));
    }

    public TaskProposalFact proposal(PrincipalInfo principal, String proposalId) {
        return proposal(principal, proposalId, "office");
    }

    public TaskProposalFact proposal(PrincipalInfo principal, String proposalId, String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskProposalFact proposal = proposals.find(proposalId).orElseThrow(() -> notFound("TASK_PROPOSAL_NOT_FOUND", "任务提案不存在"));
        if (!proposalOwned(proposal, principal, normalizedSource)) throw notFound("TASK_PROPOSAL_NOT_FOUND", "任务提案不存在");
        return proposal;
    }

    public TaskFact confirm(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return confirm(principal, proposalId, expectedVersion, "office");
    }

    public TaskFact confirm(PrincipalInfo principal, String proposalId, long expectedVersion, String requestedSource) {
        TaskProposalFact proposal = proposal(principal, proposalId, requestedSource);
        if (CONFIRMED.equals(proposal.status())) {
            TaskFact existing = taskForProposal(principal, proposalId);
            scheduleIfDue(existing);
            return existing;
        }
        resolveExpiry(proposal);
        if (!PENDING.equals(proposal.status())) throw conflict("TASK_PROPOSAL_NOT_PENDING", "任务提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        TaskProposalFact confirmed = proposals.updateIfVersion(withStatus(proposal, CONFIRMED), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
        String taskKey = "task:" + confirmed.proposalId();
        String taskPayload = materializePhaseIds(confirmed.payloadJson());
        TaskFact task = new TaskFact(UUID.randomUUID().toString(), 1, SCHEDULED, taskPayload, taskKey);
        TaskFact saved;
        try { saved = tasks.save(task); }
        catch (RuntimeException error) { saved = tasks.findByIdempotencyKey(taskKey).orElseThrow(() -> error); }
        scheduleIfDue(saved);
        return saved;
    }

    public TaskProposalFact discard(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return discard(principal, proposalId, expectedVersion, "office");
    }

    public TaskProposalFact discard(PrincipalInfo principal, String proposalId, long expectedVersion, String requestedSource) {
        TaskProposalFact proposal = proposal(principal, proposalId, requestedSource);
        if (CANCELLED.equals(proposal.status())) return proposal;
        if (!PENDING.equals(proposal.status())) throw conflict("TASK_PROPOSAL_NOT_PENDING", "任务提案已结束");
        resolveExpiry(proposal);
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        return proposals.updateIfVersion(withStatus(proposal, CANCELLED), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
    }

    public TaskFact current(PrincipalInfo principal, String taskId) {
        return current(principal, taskId, "office");
    }

    public TaskFact current(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        String normalizedSource = source(requestedSource);
        TaskFact task = tasks.find(taskId).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        if (!owns(task.payloadJson(), principal)) throw notFound("TASK_NOT_FOUND", "任务不存在");
        assertSource(task.payloadJson(), normalizedSource);
        return task;
    }

    /**
     * Returns the durable lighting device identities for a task. The controller
     * rechecks those identities against the current gateway scope before reading
     * any state; malformed legacy payloads fail closed instead of being guessed.
     */
    public List<String> lightingDeviceIds(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        TaskFact task = current(principal, taskId, requestedSource);
        JsonNode payload = safePayload(task.payloadJson());
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
        return cancel(principal, taskId, expectedVersion, "office");
    }

    public TaskFact cancel(PrincipalInfo principal, String taskId, long expectedVersion, String requestedSource) {
        TaskFact current = current(principal, taskId, requestedSource);
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
        return plan(principal, planId, "office");
    }

    public TaskPlanFact plan(PrincipalInfo principal, String planId, String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskPlanFact plan = plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在"));
        if (!owns(plan.payloadJson(), principal)) throw notFound("PLAN_NOT_FOUND", "计划不存在");
        try {
            assertSource(plan.payloadJson(), normalizedSource);
        } catch (BusinessException mismatch) {
            throw notFound("PLAN_NOT_FOUND", "计划不存在");
        }
        return plan;
    }

    public TaskPlanFact updatePlan(PrincipalInfo principal, String planId, long expectedVersion, String status, String payloadJson) {
        return updatePlan(principal, planId, expectedVersion, status, payloadJson, false, "office");
    }

    public TaskPlanFact updatePlan(PrincipalInfo principal, String planId, long expectedVersion, String status,
                                   String payloadJson, boolean confirmed) {
        return updatePlan(principal, planId, expectedVersion, status, payloadJson, confirmed, "office");
    }

    public TaskPlanFact updatePlan(PrincipalInfo principal, String planId, long expectedVersion, String status,
                                   String payloadJson, boolean confirmed, String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskPlanFact current = plan(principal, planId, normalizedSource);
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
        if ("ENABLED".equals(next) && !"PAUSED".equals(current.status()) && !"ENABLED".equals(current.status())) {
            throw new BusinessException("PLAN_FORBIDDEN", "当前计划不能重新启用", 403);
        }
        if ("PAUSED".equals(next) && !"ENABLED".equals(current.status())) {
            throw new BusinessException("PLAN_FORBIDDEN", "当前计划不能停用", 403);
        }
        if ("ENABLED".equals(next) && !"ENABLED".equals(current.status())) {
            if (!confirmed) throw new BusinessException("RESUME_CONFIRMATION_REQUIRED", "重新启用周期计划需要确认当前范围和控制绑定", 400);
            assertCurrentCatalog(principal, source(safePayload(current.payloadJson()).path("source").asText("office")),
                    current.payloadJson(), true);
            validatePlanStructure(safePayload(current.payloadJson()), current.payloadJson());
        }
        JsonNode nextPayload = payloadJson == null ? safePayload(current.payloadJson()) : safePayload(payloadJson);
        if (!nextPayload.isObject()) throw new BusinessException("INVALID_TASK_PAYLOAD", "计划内容无效", 400);
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextPayload)
                .put("tenantCode", principal.tenantCode())
                .put("ownerKey", ownerKey(principal))
                .put("source", source(safePayload(current.payloadJson()).path("source").asText("office")));
        if ("edit".equalsIgnoreCase(requested)) validatePlanStructure(nextPayload, current.payloadJson());
        return plans.updateIfVersion(new TaskPlanFact(planId, expectedVersion + 1, next,
                nextPayload.toString(), current.idempotencyKey()), expectedVersion)
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请刷新后重试"));
    }

    /**
     * Runtime-compatible direct plan cancellation. Occurrence cancellation is
     * an override/skip fact; all cancellation uses the store's transactional
     * plan/phase CAS and never rewrites terminal history.
     */
    public Map<String, Object> cancelPlan(PrincipalInfo principal, String requestedSource, String planId,
                                          long expectedVersion, String scope, LocalDate date) {
        checkPrincipal(principal);
        String normalizedSource = source(requestedSource);
        require(planId, "planId");
        if (!"occurrence".equals(scope) && !"all".equals(scope)) {
            throw new BusinessException("INVALID_CANCELLATION", "请选择有效取消范围并刷新计划", 400);
        }
        if ("occurrence".equals(scope) && date == null) {
            throw new BusinessException("INVALID_CANCELLATION", "请选择有效取消范围并刷新计划", 400);
        }
        TaskPlanFact current = plan(principal, planId, normalizedSource);
        assertCurrentCatalog(principal, normalizedSource, current.payloadJson(), true);
        if ("all".equals(scope) && "DELETED".equals(current.status()) && current.version() == expectedVersion + 1) {
            return cancellationResult(current, scope, date);
        }
        if ("occurrence".equals(scope) && current.version() == expectedVersion + 1) {
            try {
                TaskPlanOccurrence existing = plans.findOccurrence(planId, date).orElse(null);
                if (existing != null && existing.skipped() && existing.planVersion() == current.version()) {
                    return cancellationResult(current, scope, date);
                }
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生取消能力", 503);
            }
        }
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "安排已变化，请刷新后重试");
        TaskPlanFact changed;
        if ("occurrence".equals(scope)) {
            try {
                plans.overrideOccurrenceIfVersion(planId, date, expectedVersion, null, clock.instant())
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "安排已变化，请刷新后重试"));
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生取消能力", 503);
            }
            changed = plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在"));
        } else {
            try {
                changed = plans.cancelAllIfVersion(planId, expectedVersion, clock.instant())
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "安排已变化，请刷新后重试"));
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_PLAN_CANCELLATION_UNAVAILABLE", "当前存储未提供计划取消能力", 503);
            }
        }
        return cancellationResult(changed, scope, date);
    }
    private Map<String, Object> cancellationResult(TaskPlanFact plan, String scope, LocalDate date) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", plan.planId());
        result.put("version", plan.version());
        result.put("status", plan.status());
        result.put("scope", scope);
        if (date != null) result.put("date", date.toString());
        return result;
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
        if (!"TASK".equals(proposal.kind())) {
            view.put("kind", proposal.kind()); view.put("operation", proposal.operation());
            if (!"{}".equals(proposal.targetJson())) view.put("target", mapper.convertValue(safePayload(proposal.targetJson()), Object.class));
            if (!"{}".equals(proposal.beforeJson())) view.put("before", mapper.convertValue(safePayload(proposal.beforeJson()), Object.class));
            if (!"{}".equals(proposal.afterJson())) view.put("after", mapper.convertValue(safePayload(proposal.afterJson()), Object.class));
            if (proposal.expiresAt() != null) view.put("expiresAt", proposal.expiresAt().toString());
        }
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
            String phaseStatus = phase.path("status").asText("PENDING").toUpperCase();
            if (Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "EXECUTING", "CANCELLED").contains(phaseStatus)) continue;
            if (phase.path("phaseId").isTextual() && phase.path("at").isTextual()) {
                try { scheduler.schedule(task.taskId(), phase.path("phaseId").asText(), Instant.parse(phase.path("at").asText())); }
                catch (RuntimeException error) {
                    throw new BusinessException("TASK_SCHEDULER_UNAVAILABLE", "任务已记账，但调度服务暂不可用，请稍后重试", 503);
                }
            }
        }
    }
    private static boolean terminalTask(String status) {
        return status != null && Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "CANCELLED").contains(status.toUpperCase());
    }
    private void cancelScheduledPhases(TaskFact task) {
        JsonNode payloadPhases = safePayload(task.payloadJson()).path("phases");
        if (payloadPhases.isArray()) {
            for (JsonNode phase : payloadPhases) {
                String phaseStatus = phase.path("status").asText("PENDING").toUpperCase();
                if (Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "EXECUTING").contains(phaseStatus)) continue;
                if (this.phases != null && phase.path("phaseId").isTextual()) {
                    try {
                        var durable = this.phases.find(phase.path("phaseId").asText());
                        if (durable.isPresent() && Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "EXECUTING", "CANCELLED")
                                .contains(durable.get().status().toUpperCase())) continue;
                    } catch (RuntimeException ignored) { continue; }
                }
                if (phase.path("phaseId").isTextual()) {
                    try { scheduler.cancel(task.taskId(), phase.path("phaseId").asText()); } catch (RuntimeException ignored) { }
                }
            }
        } else {
            try { scheduler.cancel(task.taskId(), task.taskId()); } catch (RuntimeException ignored) { }
        }
    }

    private TaskProposalFact managementProposal(PrincipalInfo principal, String id, String kind) {
        return managementProposal(principal, id, kind, "office");
    }

    private TaskProposalFact managementProposal(PrincipalInfo principal, String id, String kind, String requestedSource) {
        String normalizedSource = source(requestedSource);
        TaskProposalFact proposal = proposals.find(id).orElseThrow(() -> notFound(kind.equals("CHANGE")
                ? "TASK_CHANGE_NOT_FOUND" : "TASK_CANCELLATION_NOT_FOUND", "任务提案不存在"));
        if (!kind.equals(proposal.kind()) || !proposalOwned(proposal, principal, normalizedSource)) {
            throw notFound(kind.equals("CHANGE") ? "TASK_CHANGE_NOT_FOUND" : "TASK_CANCELLATION_NOT_FOUND", "任务提案不存在");
        }
        return proposal;
    }

    private TaskProposalFact resolveManagement(PrincipalInfo principal, String id, long expectedVersion, String kind) {
        return resolveManagement(principal, id, expectedVersion, kind, "office");
    }

    private TaskProposalFact resolveManagement(PrincipalInfo principal, String id, long expectedVersion, String kind,
                                               String requestedSource) {
        TaskProposalFact proposal = managementProposal(principal, id, kind, requestedSource);
        if (CANCELLED.equals(proposal.status())) return proposal;
        resolveExpiry(proposal);
        if (!PENDING.equals(proposal.status()) || proposal.version() != expectedVersion) {
            throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        }
        return proposals.updateIfVersion(withStatus(proposal, CANCELLED), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
    }
    private void resolveExpiry(TaskProposalFact proposal) {
        if (proposal.expiresAt() != null && !clock.instant().isBefore(proposal.expiresAt())) {
            proposals.updateIfVersion(withStatus(proposal, "EXPIRED"), proposal.version());
            throw conflict(proposal.kind().equals("CHANGE") ? "CHANGE_EXPIRED" : "CANCELLATION_EXPIRED", "任务提案已过期，请重新提出");
        }
    }
    private void markConfirmed(TaskProposalFact proposal) {
        proposals.updateIfVersion(withStatus(proposal, CONFIRMED), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
    }
    private TaskProposalFact withStatus(TaskProposalFact proposal, String status) {
        return new TaskProposalFact(proposal.proposalId(), proposal.version() + 1, status, proposal.payloadJson(),
                proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(), proposal.beforeJson(), proposal.afterJson(),
                proposal.operation(), proposal.tenantCode(), proposal.ownerKey(), proposal.source(), proposal.expiresAt());
    }
    private boolean proposalOwned(TaskProposalFact proposal, PrincipalInfo principal, String expectedSource) {
        checkPrincipal(principal);
        String tenant = proposal.tenantCode().isBlank() ? readJson(proposal.payloadJson()).path("tenantCode").asText() : proposal.tenantCode();
        String owner = proposal.ownerKey().isBlank() ? ownerKey(principal) : proposal.ownerKey();
        return principal.tenantCode().equals(tenant) && ownerKey(principal).equals(owner)
                && source(expectedSource).equals(source(proposal.source()));
    }
    private JsonNode target(JsonNode request) {
        JsonNode value = request.path("target");
        if (!value.isObject()) throw new BusinessException("INVALID_CHANGE", "目标引用无效", 400);
        String type = value.path("type").asText("");
        if (!Set.of("task", "task_plan", "plan").contains(type)) {
            throw new BusinessException("INVALID_CHANGE", "目标引用无效", 400);
        }
        targetId(value);
        return value;
    }
    private String targetId(JsonNode target) {
        String type = target.path("type").asText("");
        String field = "task".equals(type) ? "taskId" : "planId";
        String id = target.path(field).asText("");
        require(id, "target");
        return id;
    }
    private JsonNode taskForProposal(PrincipalInfo principal, String source, String id) {
        TaskFact task = current(principal, id);
        assertSource(task.payloadJson(), source);
        return safePayload(task.payloadJson());
    }
    private JsonNode planForProposal(PrincipalInfo principal, String source, String id) {
        TaskPlanFact plan = plan(principal, id);
        assertSource(plan.payloadJson(), source);
        return safePayload(plan.payloadJson());
    }
    private TaskRecurrence.OccurrenceReference occurrenceTarget(JsonNode target) {
        if (!"task".equals(target.path("type").asText())) return null;
        return TaskRecurrence.parseOccurrenceId(target.path("taskId").asText(""));
    }
    private JsonNode occurrenceForProposal(PrincipalInfo principal, String source,
                                           TaskRecurrence.OccurrenceReference occurrence) {
        TaskPlanFact plan = plan(principal, occurrence.planId());
        assertSource(plan.payloadJson(), source);
        TaskPlanOccurrence existing;
        try {
            existing = plans.findOccurrence(occurrence.planId(), occurrence.date()).orElse(null);
        } catch (UnsupportedOperationException unavailable) {
            throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生修改能力", 409);
        }
        if (existing != null && (existing.taskId() != null || existing.skipped()
                || Set.of("CLAIMED", "MATERIALIZED").contains(existing.status()))) {
            throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "本次发生已经落库，不能再修改", 409);
        }
        JsonNode original = safePayload(plan.payloadJson());
        Instant anchor = anchor(original);
        TaskRecurrence.RepeatRule repeat = repeatRule(original.path("repeat"), TaskRecurrence.localDate(anchor));
        if (occurrence.date().isBefore(TaskRecurrence.localDate(anchor)) || !TaskRecurrence.matches(repeat, occurrence.date())) {
            throw notFound("TASK_OCCURRENCE_NOT_FOUND", "本次发生不存在");
        }
        JsonNode effective = existing != null && existing.payloadJson() != null
                ? safePayload(existing.payloadJson()) : shiftedOccurrence(original, occurrence.date(), TaskRecurrence.localDate(anchor));
        if (!effectiveAt(effective).isAfter(clock.instant())) {
            throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "本次发生已经开始，不能再修改", 409);
        }
        return effective;
    }
    private Instant anchor(JsonNode payload) {
        String value = payload.path("interval").path("startsAt").asText("");
        if (value.isBlank()) value = payload.path("phases").path(0).path("at").asText("");
        try { return Instant.parse(value); } catch (RuntimeException error) { throw new BusinessException("INVALID_CHANGE", "周期时间无效", 400); }
    }
    private Instant effectiveAt(JsonNode payload) { return anchor(payload); }
    private TaskRecurrence.RepeatRule repeatRule(JsonNode value, java.time.LocalDate anchorDate) {
        String frequency = value.path("frequency").asText("");
        Set<Integer> weekdays = Set.of();
        if (value.has("weekdays")) {
            if (!value.get("weekdays").isArray()) throw new BusinessException("INVALID_CHANGE", "重复规则无效", 400);
            var parsed = new ArrayList<Integer>();
            for (JsonNode item : value.get("weekdays")) parsed.add(item.isIntegralNumber() ? item.intValue() : -1);
            weekdays = TaskRecurrence.weekdays(parsed);
        }
        Integer day = value.has("dayOfMonth") ? value.path("dayOfMonth").asInt(-1) : null;
        java.time.LocalDate until = value.has("until") ? java.time.LocalDate.parse(value.path("until").asText()) : null;
        if (until != null && until.isBefore(anchorDate)) throw new BusinessException("INVALID_CHANGE", "截止日无效", 400);
        return new TaskRecurrence.RepeatRule(frequency, weekdays, day, until);
    }
    private JsonNode shiftedOccurrence(JsonNode original, java.time.LocalDate date, java.time.LocalDate anchorDate) {
        JsonNode copy = original.deepCopy();
        if (!copy.isObject()) throw new BusinessException("INVALID_CHANGE", "周期任务内容无效", 400);
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) copy;
        long days = java.time.temporal.ChronoUnit.DAYS.between(anchorDate, date);
        JsonNode interval = root.path("interval");
        if (interval.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) interval).put("startsAt",
                    shiftOccurrenceAt(interval.path("startsAt").asText(""), days));
            ((com.fasterxml.jackson.databind.node.ObjectNode) interval).put("endsAt",
                    shiftOccurrenceAt(interval.path("endsAt").asText(""), days));
        }
        if (!root.path("phases").isArray()) throw new BusinessException("INVALID_CHANGE", "周期任务阶段无效", 400);
        for (JsonNode phase : root.path("phases")) if (phase.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) phase).put("at",
                    shiftOccurrenceAt(phase.path("at").asText(""), days));
        }
        root.put("occurrenceDate", date.toString());
        return root;
    }
    private String shiftOccurrenceAt(String value, long days) {
        try { return Instant.parse(value).plus(java.time.Duration.ofDays(days)).toString(); }
        catch (RuntimeException error) { throw new BusinessException("INVALID_CHANGE", "周期时间无效", 400); }
    }
    private void assertSource(String payload, String expected) {
        String actual = source(safePayload(payload).path("source").asText("office"));
        if (!actual.equals(source(expected))) throw notFound("TASK_NOT_FOUND", "任务不存在");
    }
    private JsonNode changedPayload(JsonNode before, JsonNode request, String operation) {
        JsonNode after = before.deepCopy();
        if ("edit".equals(operation) && request.path("template").isObject() && after.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) after).setAll((com.fasterxml.jackson.databind.node.ObjectNode) request.path("template"));
        } else if ("skip".equals(operation) && after.isObject() && after.path("phases").isArray()) {
            var phases = mapper.createArrayNode();
            for (JsonNode phase : after.path("phases")) {
                String at = phase.path("at").asText("");
                try { if (!at.isBlank() && Instant.parse(at).isAfter(clock.instant())) phases.add(phase); } catch (RuntimeException ignored) { }
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) after).set("phases", phases);
        }
        if (after.isObject() && before.isObject()) {
            var result = (com.fasterxml.jackson.databind.node.ObjectNode) after;
            result.put("tenantCode", before.path("tenantCode").asText());
            result.put("ownerKey", before.path("ownerKey").asText());
            result.put("source", before.path("source").asText("office"));
        }
        return TaskPayloadSafety.withoutCredentials(mapper, after);
    }
    private void validateDeviceReferences(JsonNode before, JsonNode after) {
        Set<String> existing = deviceReferences(before), proposed = deviceReferences(after);
        if (!existing.isEmpty() && !existing.containsAll(proposed)) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "修改包含当前目录未核验的设备，拒绝保存", 403);
        }
    }
    private void assertCurrentCatalog(PrincipalInfo principal, String source, String payloadJson) {
        assertCurrentCatalog(principal, source, payloadJson, false);
    }

    private void assertCurrentCatalog(PrincipalInfo principal, String source, String payloadJson, boolean required) {
        if (iot == null) {
            if (required) throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
            return;
        }
        IotSource requested;
        try {
            requested = IotSource.parse(source);
        } catch (RuntimeException invalid) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
        AuthorizedDeviceScope scope;
        try {
            scope = iot.loadAuthorizedDeviceScope(new RequestIdentity(
                    principal.authorization(), principal.tenantCode(), requested, principal.ownerKey()))
                    .block(Duration.ofSeconds(30));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        if (scope == null || scope.source() != requested || scope.devices() == null) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        JsonNode payload = safePayload(payloadJson);
        String expectedCatalog = text(payload, "catalogFingerprint", "catalog_fingerprint", "deviceCatalogFingerprint");
        String expectedBinding = text(payload, "bindingFingerprint", "binding_fingerprint", "deviceBindingFingerprint");
        if ((expectedCatalog != null && !expectedCatalog.equals(scope.catalogFingerprint()))
                || (expectedBinding != null && !expectedBinding.equals(scope.bindingFingerprint()))) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务设备授权目录已变化，请重新确认", 403);
        }
        for (String id : deviceReferences(payload)) {
            AuthorizedDevice device = scope.device(id).orElse(null);
            if (device == null) {
                throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务目标已不在当前授权范围", 403);
            }
            JsonNode action = actionForDevice(payload, id);
            if (action != null && (device.observationOnly()
                    || !device.kind().equalsIgnoreCase(action.path("kind").asText()))) {
                throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务控制绑定已变化，请重新确认", 403);
            }
            if (action != null && !bindingMatches(action, device)) {
                throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务控制绑定已变化，请重新确认", 403);
            }
        }
    }
    private boolean bindingMatches(JsonNode action, AuthorizedDevice device) {
        String expected = text(action, "bindingFingerprint", "binding_fingerprint", "deviceBindingFingerprint");
        if (expected != null && !expected.equals(device.bindingFingerprint())) return false;
        return matchesOptional(action, "controlRef", device.controlRef())
                && matchesOptional(action, "officeDeviceRef", device.officeDeviceRef())
                && matchesOptional(action, "hubDeviceId", device.hubDeviceId())
                && matchesOptional(action, "smartAreaCode", device.smartAreaCode());
    }
    private boolean matchesOptional(JsonNode value, String field, String actual) {
        if (!value.has(field)) return true;
        String expected = value.path(field).isNull() ? null : value.path(field).asText(null);
        return Objects.equals(expected, actual);
    }
    private String text(JsonNode value, String... names) {
        for (String name : names) {
            JsonNode item = value.get(name);
            if (item != null && item.isTextual() && !item.asText().isBlank()) return item.asText();
        }
        return null;
    }
    private JsonNode actionForDevice(JsonNode payload, String deviceId) {
        if (!payload.path("phases").isArray()) return null;
        for (JsonNode phase : payload.path("phases")) {
            if (!phase.path("actions").isArray()) continue;
            for (JsonNode action : phase.path("actions")) {
                if (deviceId.equals(action.path("deviceId").asText(""))) return action;
            }
        }
        return null;
    }
    private Set<String> deviceReferences(JsonNode payload) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (!payload.path("phases").isArray()) return ids;
        for (JsonNode phase : payload.path("phases")) {
            if (phase.path("actions").isArray()) for (JsonNode action : phase.path("actions")) {
                if (action.path("deviceId").isTextual() && !action.path("deviceId").asText().isBlank()) ids.add(action.path("deviceId").asText());
            }
            if (phase.path("read").path("devices").isArray()) for (JsonNode device : phase.path("read").path("devices")) {
                if (device.path("id").isTextual() && !device.path("id").asText().isBlank()) ids.add(device.path("id").asText());
            }
        }
        return ids;
    }
    private void validatePlanStructure(JsonNode payload, String beforeJson) {
        if (!payload.isObject() || !payload.path("phases").isArray() || payload.path("phases").isEmpty()) {
            throw new BusinessException("INVALID_CHANGE", "任务阶段无效", 400);
        }
        Instant previous = null;
        for (JsonNode phase : payload.path("phases")) {
            if (!phase.isObject() || !phase.path("at").isTextual() || !phase.path("actions").isArray()) {
                throw new BusinessException("INVALID_CHANGE", "任务阶段结构无效", 400);
            }
            Instant at;
            try { at = Instant.parse(phase.path("at").asText()); }
            catch (RuntimeException error) { throw new BusinessException("INVALID_CHANGE", "任务阶段时间无效", 400); }
            if (previous != null && !at.isAfter(previous)) {
                throw new BusinessException("INVALID_CHANGE", "任务阶段时间必须严格递增", 400);
            }
            previous = at;
        }
        JsonNode interval = payload.path("interval");
        if (interval.isMissingNode() || interval.isNull()) {
            if (payload.has("repeat")) {
                try { repeatRule(payload.path("repeat"), TaskRecurrence.localDate(Instant.parse(payload.path("phases").get(0).path("at").asText()))); }
                catch (RuntimeException error) { throw new BusinessException("INVALID_CHANGE", "重复规则无效", 400); }
            }
            return;
        }
        try {
            Instant starts = Instant.parse(interval.path("startsAt").asText());
            Instant ends = Instant.parse(interval.path("endsAt").asText());
            if (!ends.isAfter(starts)) throw new IllegalArgumentException();
            for (JsonNode phase : payload.path("phases")) {
                Instant at = Instant.parse(phase.path("at").asText());
                if (at.isBefore(starts) || at.isAfter(ends)) throw new IllegalArgumentException();
            }
            if (payload.has("repeat")) repeatRule(payload.path("repeat"), TaskRecurrence.localDate(starts));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_CHANGE", "任务时间区间或重复规则无效", 400);
        }
        if (payload.has("repeat") && !payload.path("repeat").isObject()) {
            throw new BusinessException("INVALID_CHANGE", "重复规则无效", 400);
        }
    }

    private void validateDurableHistory(JsonNode before, JsonNode after) {
        if (phases == null || !before.path("phases").isArray() || !after.path("phases").isArray()) return;
        for (JsonNode phase : before.path("phases")) {
            String phaseId = phase.path("phaseId").asText("");
            if (phaseId.isBlank()) continue;
            var durable = phases.find(phaseId);
            if (durable.isEmpty()) continue;
            String status = durable.get().status().toUpperCase();
            if (!Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "EXECUTING", "CANCELLED").contains(status)) continue;
            JsonNode replacement = null;
            for (JsonNode candidate : after.path("phases")) {
                if (phaseId.equals(candidate.path("phaseId").asText(""))) { replacement = candidate; break; }
            }
            if (replacement == null || !replacement.equals(phase)) throw conflict("HISTORY_IMMUTABLE", "已执行阶段不可修改");
        }
    }
    private void validateFutureChange(JsonNode before, JsonNode after, String operation) {
        if (!after.path("phases").isArray()) return;
        Instant now = clock.instant();
        JsonNode beforePhases = before.path("phases");
        for (int index = 0; index < after.path("phases").size(); index++) {
            JsonNode candidate = after.path("phases").get(index);
            JsonNode previous = null;
            String id = candidate.path("phaseId").asText("");
            if (!id.isBlank() && beforePhases.isArray()) {
                for (JsonNode phase : beforePhases) {
                    if (id.equals(phase.path("phaseId").asText(""))) { previous = phase; break; }
                }
            } else if (beforePhases.isArray() && index < beforePhases.size()) {
                previous = beforePhases.get(index);
            }
            if (previous == null || !candidate.equals(previous)) {
                try {
                    if (!candidate.path("at").isTextual() || !Instant.parse(candidate.path("at").asText()).isAfter(now)) {
                        throw new BusinessException("TASK_CHANGE_NOT_FUTURE", "修改后的任务阶段必须在未来", 409);
                    }
                } catch (BusinessException failure) {
                    throw failure;
                } catch (RuntimeException error) {
                    throw new BusinessException("INVALID_CHANGE", "任务阶段时间无效", 400);
                }
            }
        }
        if (!before.path("interval").equals(after.path("interval")) && after.path("interval").isObject()) {
            try {
                if (!Instant.parse(after.path("interval").path("startsAt").asText()).isAfter(now)) {
                    throw new BusinessException("TASK_CHANGE_NOT_FUTURE", "修改后的任务区间必须在未来", 409);
                }
            } catch (BusinessException failure) {
                throw failure;
            } catch (RuntimeException error) {
                throw new BusinessException("INVALID_CHANGE", "任务时间区间无效", 400);
            }
        }
        if (beforePhases.isArray()) {
            for (JsonNode phase : beforePhases) {
                String status = phase.path("status").asText("").toUpperCase();
                if (!Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "EXECUTING").contains(status)) continue;
                String id = phase.path("phaseId").asText("");
                JsonNode replacement = null;
                for (JsonNode candidate : after.path("phases")) {
                    if (!id.isBlank() && id.equals(candidate.path("phaseId").asText(""))) { replacement = candidate; break; }
                    if (id.isBlank() && phase.path("at").asText("").equals(candidate.path("at").asText(""))) { replacement = candidate; break; }
                }
                if (replacement == null || !replacement.equals(phase)) throw conflict("HISTORY_IMMUTABLE", "已执行阶段不可修改");
            }
        }
    }
    private String materializePreservingPhaseIds(String afterJson, String beforeJson) {
        JsonNode after = safePayload(afterJson), before = safePayload(beforeJson);
        if (!after.isObject() || !before.path("phases").isArray() || !after.path("phases").isArray()) return after.toString();
        var original = before.path("phases");
        var phases = (com.fasterxml.jackson.databind.node.ArrayNode) after.path("phases");
        for (int i = 0; i < phases.size(); i++) {
            var phase = (com.fasterxml.jackson.databind.node.ObjectNode) phases.get(i);
            if (!phase.path("phaseId").isTextual() && i < original.size() && original.get(i).path("phaseId").isTextual()) phase.put("phaseId", original.get(i).path("phaseId").asText());
        }
        return after.toString();
    }
    private Map<String, Object> managementResult(TaskProposalFact proposal) {
        return managementResult(proposal, targetId(readJson(proposal.targetJson())), null);
    }
    private Map<String, Object> managementResult(TaskProposalFact proposal, String taskId, String planId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(proposal.kind().equals("CHANGE") ? "changeId" : "cancellationId", proposal.proposalId());
        if (planId != null) result.put("planId", planId); else result.put("taskId", taskId);
        result.put("status", proposal.status().equals(PENDING) ? CONFIRMED : proposal.status());
        return result;
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
