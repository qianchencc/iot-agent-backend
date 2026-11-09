package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.auth.ScopedKey;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.task.TaskDetailProjector.PlanSource;
import ltd.qianc.iotagent.task.internal.TaskReadCatalog;
import ltd.qianc.iotagent.task.internal.TaskProposalNormalizer;

/**
 * Public task boundary. It performs proposal/plan/task CAS and idempotency while
 * execution, scheduling and durable facts stay behind injected adapters. Every
 * projection is the Runtime public DTO: lowercase statuses, per-device rows,
 * occurrence identities and authorized trimming instead of owner-only reads.
 */
public final class TaskFacade implements ltd.qianc.iotagent.conversation.ConversationTaskCards, TaskReadScope.Provider {
    private static final Set<String> EDIT_ROOT_SPECIAL_FIELDS = Set.of(
            "label", "title", "repeat", "interval", "phases", "normalizedAt", "createdAt", "checkpointCount", "taskKind");
    private static final Set<String> EDIT_INTERVAL_TIME_FIELDS = Set.of("startsAt", "endsAt");
    private static final Set<String> EDIT_PHASE_SPECIAL_FIELDS = Set.of(
            "at", "actions", "read", "phaseId", "status", "startedAt", "endedAt", "outcome", "failureReason");
    private static final String PENDING = "PENDING_CONFIRMATION";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final String SCHEDULED = "SCHEDULED";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ObjectMapper mapper;
    private final TaskFactStore tasks;
    private final TaskProposalStore proposals;
    private final TaskPlanStore plans;
    private final TaskNotificationStore notifications;
    private final TaskScheduler scheduler;
    private final TaskPhaseStore phases;
    private final IotAdapter iot;
    private final TaskReadCatalog readCatalog;
    private final ReadSnapshots snapshots;
    private final TaskExecutionIdentityStore executionIdentities;
    private final TaskProposalNormalizer normalizer;
    private final TaskAtomicTransactions atomic;
    private final TaskDetailProjector projector;
    private final AuditEventStore audit;
    private final Clock clock;

    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, null, null, null, null, null,
                TaskAtomicTransactions.direct(), null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot,
                      ReadSnapshots snapshots, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, phases, iot, null, null, snapshots,
                TaskAtomicTransactions.direct(), null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot,
                      TaskExecutionIdentityStore executionIdentities, Duration scopeTimeout,
                      ReadSnapshots snapshots, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, phases, iot,
                executionIdentities, scopeTimeout, snapshots, TaskAtomicTransactions.direct(), null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot,
                      TaskExecutionIdentityStore executionIdentities, Duration scopeTimeout,
                      ReadSnapshots snapshots, TaskAtomicTransactions atomic, Clock clock) {
        this(mapper, tasks, proposals, plans, notifications, scheduler, phases, iot,
                executionIdentities, scopeTimeout, snapshots, atomic, null, clock);
    }
    public TaskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                      TaskPlanStore plans, TaskNotificationStore notifications,
                      TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot,
                      TaskExecutionIdentityStore executionIdentities, Duration scopeTimeout,
                      ReadSnapshots snapshots, TaskAtomicTransactions atomic, AuditEventStore audit, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.phases = phases;
        this.iot = iot;
        this.readCatalog = iot == null ? null : new TaskReadCatalog(iot, Objects.requireNonNull(snapshots, "snapshots"));
        this.snapshots = snapshots;
        this.executionIdentities = executionIdentities;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.atomic = atomic == null ? TaskAtomicTransactions.direct() : atomic;
        this.normalizer = new TaskProposalNormalizer(mapper, iot, clock, scopeTimeout);
        this.projector = new TaskDetailProjector(this.mapper, this::durablePhases);
        this.audit = audit;
    }

    private List<TaskDetailProjector.PhaseRow> durablePhases(String taskId) {
        if (phases == null) return List.of();
        List<TaskPhaseStore.PhaseResult> rows;
        try {
            rows = phases.allPhases(taskId);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            // A failing phase read must never look like "task has no phases";
            // projections fail closed with the Runtime unavailable contract.
            throw new BusinessException("TASK_PHASES_UNAVAILABLE", "任务阶段暂不可读取", 503);
        }
        return rows.stream()
                .map(row -> new TaskDetailProjector.PhaseRow(row.phaseId(), row.dueAt(), row.status(),
                        readJson(row.definitionJson()), readJson(row.resultJson()),
                        row.startedAt(), row.endedAt(), row.failureReason()))
                .toList();
    }

    // ------------------------------------------------------------- proposals

    public TaskProposalFact propose(PrincipalInfo principal, String source, String conversationId,
                                    String turnId, String payloadJson, String idempotencyKey) {
        checkPrincipal(principal); String normalizedSource = source(source);
        require(conversationId, "conversationId"); require(turnId, "turnId"); require(idempotencyKey, "idempotencyKey");
        String key = scoped(principal, idempotencyKey);
        Optional<TaskProposalFact> prior = proposals.findByIdempotencyKey(key);
        if (prior.isPresent() && "TASK".equals(prior.get().kind())
                && proposalOwned(prior.get(), principal, normalizedSource)) return prior.get();
        if (prior.isPresent()) throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他任务来源");
        JsonNode request = safePayload(payloadJson);
        if (request.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("conversationId", conversationId);
        JsonNode payload = safePayload(normalizer.normalizeProposal(principal, normalizedSource, request.toString()));
        rejectDuplicatePlanShape(principal, normalizedSource, payload);
        if (payload.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                    .put("conversationId", conversationId).put("turnId", turnId)
                    .put("createdAt", clock.instant().toString())
                    .put("creatorName", creatorName(principal, normalizedSource));
            bindAuditPrincipal((com.fasterxml.jackson.databind.node.ObjectNode) payload, principal);
        }

        Instant expiresAt = clock.instant().plus(java.time.Duration.ofMinutes(15));
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), 1, PENDING, payload.toString(), key,
                "TASK", "{}", "{}", payload.toString(), "create", principal.tenantCode(), ownerKey(principal),
                normalizedSource, expiresAt);
        return saveAuditedProposal(principal, normalizedSource, "TASK_PROPOSED", created);
    }

    private TaskProposalFact saveAuditedProposal(PrincipalInfo principal, String source,
            String eventType, TaskProposalFact created) {
        return atomic.write(() -> {
            TaskProposalFact saved;
            try {
                saved = proposals.save(created);
            } catch (RuntimeException failure) {
                saved = proposals.findByIdempotencyKey(created.idempotencyKey()).orElseThrow(() -> failure);
                if (!proposalOwned(saved, principal, source)) {
                    throw conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他任务来源");
                }
            }
            auditProposal(principal, source, eventType, saved);
            return saved;
        });
    }

    private static void bindAuditPrincipal(com.fasterxml.jackson.databind.node.ObjectNode payload, PrincipalInfo principal) {
        payload.remove(List.of("principalId", "identityRealm"));
        if (principal.verified()) {
            payload.put("principalId", principal.principalId());
            payload.put("identityRealm", principal.identityRealm());
        }
    }

    /**
     * Runtime creator display name: read from the SSO account server for Office
     * ("成员" when the metadata call fails), empty for Company; the model never
     * authors this field.
     */
    private String creatorName(PrincipalInfo principal, String normalizedSource) {
        if (iot == null || !"office".equals(normalizedSource)) return "";
        try {
            String name = iot.readCreatorDisplayName(new RequestIdentity(
                    principal.authorization(), principal.tenantCode(), IotSourceEnum.OFFICE, ownerKey(principal)))
                    .block(Duration.ofSeconds(10));
            return name == null || name.isBlank() ? "成员" : name;
        } catch (RuntimeException failure) {
            return "成员";
        }
    }

    /**
     * Runtime pi-runner proposeTask duplicate check: an enabled plan whose repeat
     * rule and phase shape match returns already_scheduled instead of registering
     * a second plan; the proposal itself is never saved for that request.
     */
    private void rejectDuplicatePlanShape(PrincipalInfo principal, String source, JsonNode payload) {
        if (!payload.path("repeat").isObject()) return;
        String shape = ltd.qianc.iotagent.task.internal.PlanSignatures.shape(
                payload.path("repeat"), payload.path("phases"));
        for (TaskPlanFact plan : plans.list(principal.tenantCode(), ownerKey(principal), source)) {
            if (!"ENABLED".equalsIgnoreCase(plan.status()) || !owns(plan.payloadJson(), principal)) continue;
            JsonNode planPayload = safePayload(plan.payloadJson());
            if (shape.equals(ltd.qianc.iotagent.task.internal.PlanSignatures.shape(
                    planPayload.path("repeat"), planPayload.path("phases")))) {
                String nextAt = null;
                try {
                    PlanSource planSource = planSource(plan);
                    String checkpoint = projector.nextCheckpointAt(planSource);
                    nextAt = checkpoint == null && planSource.nextAt() != null
                            ? planSource.nextAt().toString() : checkpoint;
                } catch (RuntimeException ignored) {
                    // The duplicate answer must not depend on the optional pointer projection.
                }
                throw new TaskAlreadyScheduledException(plan.planId(),
                        planPayload.path("label").asText(""), nextAt);
            }
        }
    }

    /**
     * Runtime taskConfirmationContext (app.ts): the most recent confirmed task facts of
     * one conversation, newest first, as read-only context. `confirmation`/`confirmedAt`
     * only appear when the stored payload carries them; the original goal stays with
     * the conversation turn it came from.
     */
    public List<Map<String, Object>> recentConfirmations(PrincipalInfo principal, String conversationId,
            String source, int limit) {
        checkPrincipal(principal);
        require(conversationId, "conversationId");
        String normalizedSource = source(source);
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskProposalFact proposal : proposals.listByConversation(
                conversationId, principal.tenantCode(), ownerKey(principal), normalizedSource)) {
            if (!"TASK".equals(proposal.kind()) || !CONFIRMED.equals(proposal.status())) continue;
            JsonNode payload = safePayload(proposal.payloadJson());
            if (!conversationId.equals(payload.path("conversationId").asText(""))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("proposalId", proposal.proposalId());
            if (payload.path("taskId").isTextual()) row.put("taskId", payload.path("taskId").asText());
            row.put("label", payload.path("label").asText(""));
            row.put("status", proposal.status());
            List<String> plannedAt = new ArrayList<>();
            for (JsonNode phase : payload.path("phases")) {
                if (phase.path("at").isTextual()) plannedAt.add(phase.path("at").asText());
            }
            row.put("plannedAt", plannedAt);
            if (payload.path("confirmedAt").isTextual()) row.put("confirmedAt", payload.path("confirmedAt").asText());
            if (payload.path("confirmation").isObject()) {
                row.put("confirmation", mapper.convertValue(publicPayload(payload.path("confirmation")), Object.class));
            }
            result.add(row);
            if (result.size() >= limit) break;
        }
        return result;
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
        if ("edit".equals(operation)
                && (!request.path("template").isObject() || request.path("template").isEmpty())) {
            throw new BusinessException("INVALID_CHANGE",
                    "修改内容为空，未生成提案；请补充实际要修改的名称、时间或执行要求后重新提案", 400);
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
                : plan ? planForProposal(principal, normalizedSource, planId)
                        : taskPayloadForProposal(principal, normalizedSource, id);
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
        if ("edit".equals(operation) && sameEditDefinition(before, after)) {
            throw new BusinessException("TASK_CHANGE_UNCHANGED",
                    "修改后的安排与当前相同，未生成提案；请核对用户要求并补充实际变更", 400);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("conversationId", conversationId).put("turnId", turnId);
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), targetVersion, PENDING, request.toString(), key,
                "CHANGE", target.toString(), before.toString(), after.toString(), operation, principal.tenantCode(),
                ownerKey(principal), normalizedSource, clock.instant().plus(java.time.Duration.ofMinutes(15)));
        return saveAuditedProposal(principal, normalizedSource, "TASK_CHANGE_PROPOSED", created);
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
        // Runtime proposeCancellation: the actor may propose cancelling any tenant
        // task whose devices stay inside the authorized catalog, not only own tasks.
        TaskFact current = current(principal, taskId, normalizedSource);
        assertCancelScope(principal, normalizedSource, current);
        String key = scoped(principal, idempotencyKey);
        Optional<TaskProposalFact> prior = proposals.findByIdempotencyKey(key);
        if (prior.isPresent()) {
            if (!proposalOwned(prior.get(), principal, normalizedSource)) throw notFound("TASK_CANCELLATION_NOT_FOUND", "任务取消提案不存在");
            return prior.get();
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("conversationId", conversationId).put("turnId", turnId);
        TaskProposalFact created = new TaskProposalFact(UUID.randomUUID().toString(), current.version(), PENDING, request.toString(), key,
                "CANCELLATION", target.toString(), current.payloadJson(), current.payloadJson(), "cancel",
                principal.tenantCode(), ownerKey(principal), normalizedSource, clock.instant().plus(java.time.Duration.ofMinutes(15)));
        return saveAuditedProposal(principal, normalizedSource, "TASK_CANCELLATION_PROPOSED", created);
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
                    changed = atomic.write(() -> {
                        TaskPlanOccurrence written = plans.overrideOccurrenceIfVersion(planId, occurrence.date(),
                                current.version(), override, clock.instant())
                                .orElseThrow(() -> conflict("VERSION_CONFLICT", "本次发生已变化或已开始，请刷新后重试"));
                        markConfirmed(principal, proposal);
                        return written;
                    });
                } catch (UnsupportedOperationException unavailable) {
                    throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生修改能力", 409);
                }
                return managementResult(proposal, null, changed.planId());
            }
            if ("delete".equals(proposal.operation())) {
                TaskPlanFact deleted;
                try {
                    deleted = atomic.write(() -> {
                        // Same whole-plan cancellation mutation as cancelPlan:
                        // the store cancels every materialized task, so the
                        // creator notifications and identity drops settle here too.
                        List<TaskFact> materialized = materializedPlanTasks(planId);
                        TaskPlanFact written = plans.cancelAllIfVersion(planId, current.version(), clock.instant())
                                .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请重新提出修改"));
                        for (TaskFact task : materialized) settleCancelledTask(task);
                        markConfirmed(principal, proposal);
                        return written;
                    });
                } catch (UnsupportedOperationException unavailable) {
                    throw new BusinessException("TASK_PLAN_CANCELLATION_UNAVAILABLE", "当前存储未提供计划取消能力", 503);
                }
                return managementResult(proposal, null, deleted.planId());
            }
            String nextStatus = switch (proposal.operation()) {
                case "pause" -> "PAUSED";
                case "resume" -> "ENABLED";
                case "delete" -> "DELETED";
                default -> current.status();
            };
            TaskPlanFact updated = atomic.write(() -> {
                TaskPlanFact written = plans.updateIfVersion(new TaskPlanFact(planId, current.version() + 1, nextStatus,
                        safePayload(proposal.afterJson()).toString(), current.idempotencyKey()), current.version())
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请重新提出修改"));
                if ("resume".equals(proposal.operation()) && executionIdentities != null) {
                    // Runtime plans.manage resume rebinds the plan credential to
                    // the confirming principal with the ENABLED flip.
                    executionIdentities.saveForPlan(planId, identity(principal));
                }
                markConfirmed(principal, proposal);
                return written;
            });
            return managementResult(proposal, null, updated.planId());
        }
        TaskFact current = ownedCurrent(principal, id, normalizedSource);
        if (current.version() != proposal.version()) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出修改");
        if (terminalTask(current.status())) throw conflict("TASK_NOT_EDITABLE", "已结束任务不可修改");
        String status = switch (proposal.operation()) {
            case "pause" -> "PAUSED";
            case "resume" -> SCHEDULED;
            case "delete" -> CANCELLED;
            default -> current.status().equals("PAUSED") ? "PAUSED" : SCHEDULED;
        };
        TaskFact updated = atomic.write(() -> {
            TaskFact replacement = tasks.updateIfVersion(new TaskFact(id, current.version() + 1, status,
                    materializePreservingPhaseIds(proposal.afterJson(), current.payloadJson()), current.idempotencyKey()), current.version())
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务已变化，请重新提出修改"));
            if ("delete".equals(proposal.operation()) || "skip".equals(proposal.operation())) cancelScheduledPhases(replacement);
            else if ("edit".equals(proposal.operation())) cancelReplacedPhases(current, replacement);
            if (!"pause".equals(proposal.operation()) && !"delete".equals(proposal.operation())) scheduleIfDue(replacement);
            markConfirmed(principal, proposal);
            return replacement;
        });
        return managementResult(proposal, updated.taskId(), null);
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
        if (CONFIRMED.equals(proposal.status())) return cancellationReceipt(proposal, targetId(readJson(proposal.targetJson())));
        resolveExpiry(proposal);
        if (!PENDING.equals(proposal.status())) throw conflict("CANCELLATION_NOT_PENDING", "取消提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出取消");
        JsonNode target = readJson(proposal.targetJson());
        TaskFact current = current(principal, targetId(target), normalizedSource);
        assertCancelScope(principal, normalizedSource, current);
        assertCurrentCatalog(principal, proposal.source(), current.payloadJson());
        if (current.version() != proposal.version()) throw conflict("VERSION_CONFLICT", "任务已变化，请重新提出取消");
        TaskFact cancelled = atomic.write(() -> {
            TaskFact result = cancelValidated(current);
            markConfirmed(principal, proposal);
            return result;
        });
        return cancellationReceipt(proposal, cancelled.taskId());
    }

    /** Runtime task-cancellations confirm receipt: task result plus the proposal status. */
    private Map<String, Object> cancellationReceipt(TaskProposalFact proposal, String taskId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cancellationId", proposal.proposalId());
        result.put("taskId", taskId);
        result.put("status", "cancelled");
        result.put("proposalStatus", CONFIRMED);
        return result;
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

    /**
     * Human-confirmed creation of either a one-time/interval task or a recurring
     * plan. The proposal CAS, fact materialization, execution identity and
     * scheduling commit in one transaction; the created fact reuses the proposal
     * id exactly like the Runtime receipt, so a lost response replays to the same
     * identity through the idempotency key alone.
     */
    public Map<String, Object> confirmProposal(PrincipalInfo principal, String proposalId, long expectedVersion) {
        return confirmProposal(principal, proposalId, expectedVersion, "office");
    }

    public Map<String, Object> confirmProposal(PrincipalInfo principal, String proposalId, long expectedVersion,
                                               String requestedSource) {
        TaskProposalFact proposal = proposal(principal, proposalId, requestedSource);
        if (!"TASK".equals(proposal.kind())) throw notFound("TASK_PROPOSAL_NOT_FOUND", "任务提案不存在");
        if (CONFIRMED.equals(proposal.status())) {
            return atomic.write(() -> materializeConfirmed(principal, proposal));
        }
        if ("EXPIRED".equals(proposal.status()) || PENDING.equals(proposal.status())
                && proposal.expiresAt() != null && !clock.instant().isBefore(proposal.expiresAt())) {
            if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
            JsonNode original = safePayload(proposal.payloadJson());
            boolean future = Instant.parse(original.path("phases").get(0).path("at").asText()).isAfter(clock.instant())
                    || original.path("repeat").isObject() && original.path("interval").isObject()
                    && Instant.parse(original.path("interval").path("endsAt").asText()).isAfter(clock.instant());
            if (future) {
                Instant expiresAt = clock.instant().plus(Duration.ofMinutes(15));
                TaskProposalFact reopened = new TaskProposalFact(proposal.proposalId(), proposal.version() + 1,
                        PENDING, proposal.payloadJson(), proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(),
                        proposal.beforeJson(), proposal.afterJson(), proposal.operation(), proposal.tenantCode(),
                        proposal.ownerKey(), proposal.source(), expiresAt);
                proposals.updateIfVersion(reopened, proposal.version())
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
                throw new BusinessException("TASK_RECONFIRM_REQUIRED", "提案已更新，请重新确认",
                        Map.of("kind", "reconfirm", "version", reopened.version(), "expiresAt", expiresAt.toString()), 409);
            }
            if (PENDING.equals(proposal.status())) proposals.updateIfVersion(withStatus(proposal, "EXPIRED"), proposal.version());
            throw conflict("TASK_EXPIRED", "原定时间已过，需要新的执行时间；原任务目标已保留");
        }
        if (!PENDING.equals(proposal.status())) throw conflict("TASK_PROPOSAL_NOT_PENDING", "任务提案已结束");
        if (proposal.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试");
        JsonNode payload = safePayload(proposal.payloadJson());
        validatePlanStructure(payload, payload.toString());
        validateConfirmTime(payload);
        assertCurrentCatalog(principal, proposal.source(), proposal.payloadJson(), true);
        return atomic.write(() -> {
            TaskProposalFact confirmed = proposals.updateIfVersion(withStatus(proposal, CONFIRMED), proposal.version())
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
            auditProposal(principal, proposal.source(), "TASK_PROPOSAL_CONFIRMED", confirmed);
            return materializeConfirmed(principal, confirmed);
        });
    }

    /** Runtime confirm receipt: one-time task or plan with next pointers, lowercase status. */
    private Map<String, Object> materializeConfirmed(PrincipalInfo principal, TaskProposalFact confirmed) {
        JsonNode payload = safePayload(confirmed.payloadJson());
        validatePlanStructure(payload, payload.toString());
        bindAuditPrincipal((com.fasterxml.jackson.databind.node.ObjectNode) payload, principal);
        TaskExecutionIdentityStore.TaskExecutionIdentity identity = identity(principal);
        if (payload.path("repeat").isObject()) {
            String key = "plan:" + confirmed.proposalId();
            TaskPlanFact plan = plans.findByIdempotencyKey(key).orElseGet(() -> {
                TaskPlanFact candidate = new TaskPlanFact(confirmed.proposalId(), 1, "ENABLED",
                        payload.toString(), key);
                try {
                    return plans.save(candidate);
                } catch (RuntimeException error) {
                    return plans.findByIdempotencyKey(key).orElseThrow(() -> error);
                }
            });
            if (executionIdentities != null) executionIdentities.saveForPlan(plan.planId(), identity);
            PlanSource source = planSource(plan);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", confirmed.proposalId());
            result.put("status", "scheduled");
            result.put("version", plan.version());
            result.put("planId", plan.planId());
            result.put("taskKind", "recurring");
            result.put("nextAt", source.nextAt() == null ? null : source.nextAt().toString());
            result.put("nextCheckpointAt", projector.nextCheckpointAt(source));
            result.put("nextDate", source.nextDate() == null ? null : source.nextDate().toString());
            return result;
        }
        String key = "task:" + confirmed.proposalId();
        TaskFact task = atomic.write(() -> {
            TaskFact materialized = tasks.findByIdempotencyKey(key).orElseGet(() -> {
                TaskFact candidate = new TaskFact(confirmed.proposalId(), 1, SCHEDULED,
                        materializePhaseIds(payload.toString()), key);
                try {
                    return tasks.save(candidate);
                } catch (RuntimeException error) {
                    return tasks.findByIdempotencyKey(key).orElseThrow(() -> error);
                }
            });
            if (executionIdentities != null) executionIdentities.saveForTask(materialized.taskId(), identity);
            scheduleIfDue(materialized);
            return materialized;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.taskId());
        result.put("status", "scheduled");
        result.put("version", task.version());
        return result;
    }

    private TaskExecutionIdentityStore.TaskExecutionIdentity identity(PrincipalInfo principal) {
        return new TaskExecutionIdentityStore.TaskExecutionIdentity(
                principal.tenantCode(), ownerKey(principal), principal.authorization());
    }

    private void validateConfirmTime(JsonNode payload) {
        JsonNode phases = payload.path("phases");
        if (!phases.isArray() || phases.isEmpty()) throw new BusinessException("INVALID_TASK_PAYLOAD", "任务阶段无效", 400);
        Instant now = clock.instant();
        if (payload.path("repeat").isObject()) {
            try {
                TaskRecurrence.RepeatRule rule = repeatRule(payload.path("repeat"), TaskRecurrence.localDate(anchor(payload)));
                if (TaskRecurrence.next(rule, anchor(payload), now) == null) {
                    throw new BusinessException("TASK_TIME_PASSED", "截止日前没有未来发生", 409);
                }
            } catch (BusinessException failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                throw new BusinessException("INVALID_TASK_PAYLOAD", "重复规则无效", 400);
            }
            return;
        }
        JsonNode interval = payload.path("interval");
        if (interval.isObject()) {
            try {
                if (!Instant.parse(interval.path("endsAt").asText()).isAfter(now)) {
                    throw new BusinessException("TASK_TIME_PASSED", "任务时间区间已过，请重新选择时间", 409);
                }
            } catch (BusinessException failure) {
                throw failure;
            } catch (RuntimeException invalid) {
                throw new BusinessException("INVALID_TASK_PAYLOAD", "任务时间区间无效", 400);
            }
        }
        // Runtime ten-minute rule (tasks/store.ts confirmProposal): a windowless
        // task stays confirmable for ten minutes after its first checkpoint,
        // while interval tasks keep requiring a still-future checkpoint so a
        // partially elapsed window confirms only on its remaining points.
        Instant earliest = interval.isObject() ? now : now.minus(java.time.Duration.ofMinutes(10));
        for (JsonNode phase : phases) {
            try {
                Instant at = Instant.parse(phase.path("at").asText());
                if (interval.isObject() ? at.isAfter(now) : !at.isBefore(earliest)) return;
            } catch (RuntimeException invalid) {
                throw new BusinessException("INVALID_TASK_PAYLOAD", "任务阶段时间无效", 400);
            }
        }
        throw new BusinessException("TASK_TIME_PASSED", "任务时间已过，请重新选择执行时间", 409);
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

    /** Supersedes only unconfirmed cards; registered tasks and enabled plans are never cancelled here. */
    public List<TaskProposalFact> retireConversation(PrincipalInfo principal, String conversationId, String requestedSource) {
        checkPrincipal(principal);
        List<TaskProposalFact> retired = proposals.retireForConversation(conversationId, principal.tenantCode(),
                ownerKey(principal), source(requestedSource));
        for (TaskProposalFact proposal : retired) {
            if (!proposalOwned(proposal, principal, requestedSource)
                    || !conversationId.equals(safePayload(proposal.payloadJson()).path("conversationId").asText(""))) {
                throw new BusinessException("TASK_PROPOSAL_SCOPE_MISMATCH", "任务提案归属不匹配", 403);
            }
        }
        return retired;
    }

    // -------------------------------------------------------- task read views

    public TaskFact current(PrincipalInfo principal, String taskId) {
        return current(principal, taskId, "office");
    }

    public TaskFact current(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        String normalizedSource = source(requestedSource);
        TaskFact task = tasks.find(taskId).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        JsonNode payload = readJson(task.payloadJson());
        if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        assertSource(task.payloadJson(), normalizedSource);
        return task;
    }

    /**
     * Runtime GET /tasks/:id: real task, materialized occurrence replacement behind
     * an {@code occurrence:planId:date} id, or the virtual plan occurrence — always
     * the full authorized lowercase detail, 404 when nothing is visible.
     */
    public Map<String, Object> taskView(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        String normalizedSource = source(requestedSource);
        TaskRecurrence.OccurrenceReference occurrence = TaskRecurrence.parseOccurrenceId(taskId);
        if (occurrence != null) return occurrenceView(principal, occurrence, normalizedSource);
        TaskFact task = tasks.find(taskId).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        return taskFactView(principal, task, normalizedSource);
    }

    private Map<String, Object> occurrenceView(PrincipalInfo principal, TaskRecurrence.OccurrenceReference occurrence,
                                               String normalizedSource) {
        Optional<TaskFact> materialized = tasks.findByIdempotencyKey(
                TaskRecurrence.occurrenceId(occurrence.planId(), occurrence.date()));
        if (materialized.isPresent()) return taskFactView(principal, materialized.get(), normalizedSource);
        TaskPlanFact plan = plans.find(occurrence.planId()).orElseThrow(() -> notFound("TASK_NOT_FOUND", "任务不存在"));
        JsonNode payload = readJson(plan.payloadJson());
        if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        if (!"ENABLED".equalsIgnoreCase(plan.status())) throw notFound("TASK_NOT_FOUND", "任务不存在");
        if (!normalizedSource.equals(source(payload.path("source").asText("office")))) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        TaskReadScope scope = scope(principal, normalizedSource);
        Map<String, Object> view = projector.virtualOccurrence(planSource(plan), occurrence.date(),
                scope, ownerKey(principal), false);
        if (view == null) throw notFound("TASK_NOT_FOUND", "任务不存在");
        return view;
    }

    /** Authorized detail of one durable task fact; invisible rows are TASK_NOT_FOUND. */
    public Map<String, Object> taskFactView(PrincipalInfo principal, TaskFact task, String requestedSource) {
        JsonNode payload = readJson(task.payloadJson());
        if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        if (!source(requestedSource).equals(source(payload.path("source").asText("office")))) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        TaskReadScope scope = scope(principal, source(requestedSource));
        Map<String, Object> view = projector.detail(taskSource(task), scope, ownerKey(principal),
                planMetadata(payload));
        if (view == null) throw notFound("TASK_NOT_FOUND", "任务不存在");
        return view;
    }

    private TaskDetailProjector.TaskSource taskSource(TaskFact task) {
        JsonNode payload = readJson(task.payloadJson());
        return new TaskDetailProjector.TaskSource(task.taskId(), task.version(), task.status(), payload,
                instantOf(payload.path("createdAt")), instantOf(payload.path("normalizedAt")));
    }

    private Instant instantOf(JsonNode value) {
        return value.isTextual() ? parseInstant(value.asText()) : null;
    }

    private Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private Map<String, Object> planMetadata(JsonNode taskPayload) {
        String planId = taskPayload.path("sourcePlanId").asText("");
        if (planId.isBlank()) return Map.of();
        TaskPlanFact plan = plans.find(planId).orElse(null);
        if (plan == null) return Map.of();
        PlanSource source = planSource(plan);
        Map<String, Object> metadata = new LinkedHashMap<>();
        JsonNode payload = source.payload();
        if (payload.path("repeat").isObject()) metadata.put("repeat", publicValue(payload.path("repeat")));
        if (source.nextAt() != null) metadata.put("nextAt", source.nextAt().toString());
        String occurrenceDate = taskPayload.path("occurrenceDate").asText("");
        if (!occurrenceDate.isBlank()) {
            metadata.put("occurrence", Map.of("planId", planId, "date", occurrenceDate, "virtual", false));
        }
        return metadata;
    }

    private PlanSource planSource(TaskPlanFact plan) {
        try {
            TaskPlanStore.PlanPointer pointer = plans.pointer(plan.planId()).orElse(null);
            Map<LocalDate, TaskDetailProjector.OccurrenceState> occurrences = new LinkedHashMap<>();
            for (TaskPlanOccurrence occurrence : plans.occurrences(plan.planId())) {
                occurrences.put(occurrence.occurrenceDate(),
                        new TaskDetailProjector.OccurrenceState(occurrence.occurrenceDate(), occurrence.taskId(),
                                occurrence.status(), occurrence.payloadJson() == null ? null : readJson(occurrence.payloadJson())));
            }
            JsonNode payload = readJson(plan.payloadJson());
            return new PlanSource(plan.planId(), plan.version(), plan.status(), payload,
                    pointer == null ? null : pointer.nextAt(), pointer == null ? null : pointer.nextDate(),
                    instantOf(payload.path("createdAt")), pointer == null ? null : pointer.updatedAt(), occurrences);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new BusinessException("TASK_PLAN_UNAVAILABLE", "周期计划状态暂不可读取", 503);
        }
    }

    private TaskFact ownedCurrent(PrincipalInfo principal, String taskId, String requestedSource) {
        TaskFact task = current(principal, taskId, requestedSource);
        if (!owns(task.payloadJson(), principal)) throw notFound("TASK_NOT_FOUND", "任务不存在");
        return task;
    }

    /** Lighting identities from durable task facts, trimmed to the current local authorization. */
    public List<String> lightingDeviceIds(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        return lightingDeviceIds(taskView(principal, taskId, requestedSource));
    }

    /** Current local observations, separate from the task's durable execution readbacks. */
    public Map<String, Object> lightingStates(PrincipalInfo principal, String taskId, String requestedSource) {
        checkPrincipal(principal);
        if (iot == null) throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "物联设备状态适配器未接入", 503);
        IotSourceEnum requested;
        try { requested = IotSourceEnum.parse(source(requestedSource)); }
        catch (RuntimeException invalid) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400); }
        Map<String, Object> view = taskView(principal, taskId, requested.wireValue());
        List<String> deviceIds = lightingDeviceIds(view);
        if (deviceIds.isEmpty()) return Map.of("status", "empty", "states", List.of());
        assertHeldLightingScope(principal, requested, deviceIds);
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), requested, ownerKey(principal));
        return taskStatesView(deviceStatesBody(identity, requested, deviceIds), deviceIds);
    }

    /** Stops exposing states once the held catalog revokes a device or re-purposes its kind. */
    private void assertHeldLightingScope(PrincipalInfo principal, IotSourceEnum requested, List<String> deviceIds) {
        TaskReadScope held = scope(principal, requested.wireValue());
        for (String deviceId : deviceIds) {
            TaskReadScope.DeviceView device = held.device(deviceId).orElse(null);
            if (device == null || !"lighting".equalsIgnoreCase(device.kind())) {
                throw notFound("TASK_NOT_FOUND", "任务不存在");
            }
        }
    }

    /** Lighting identities of one already-authorized task view, in projection order. */
    private static List<String> lightingDeviceIds(Map<String, Object> view) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (view.get("phases") instanceof List<?> phases) {
            for (Object phaseObject : phases) {
                if (!(phaseObject instanceof Map<?, ?> phase)) continue;
                if (!(phase.get("devices") instanceof List<?> devices)) continue;
                for (Object deviceObject : devices) {
                    if (deviceObject instanceof Map<?, ?> device
                            && "lighting".equalsIgnoreCase(String.valueOf(device.get("kind")))
                            && device.get("id") instanceof String id && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        }
        return List.copyOf(ids);
    }


    /** Rechecks local membership before reading persisted current state; never opens a source request. */
    private Map<String, Object> deviceStatesBody(RequestIdentity identity, IotSourceEnum requested, List<String> deviceIds) {
        AuthorizedDeviceScope scope;
        try {
            scope = iot.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(30));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "读取任务设备授权范围失败", 503);
        }
        if (scope == null || scope.source() != requested) {
            throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "读取任务设备授权范围失败", 503);
        }
        for (String deviceId : deviceIds) {
            AuthorizedDevice device = scope.device(deviceId).orElse(null);
            if (device == null || !"lighting".equalsIgnoreCase(device.kind())) throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        DeviceStateReadResult result;
        try {
            result = iot.readDeviceStates(identity, deviceIds).block(Duration.ofSeconds(30));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "读取设备状态失败", 503);
        }
        if (result == null) throw new BusinessException("TASK_LIGHTING_STATES_UNAVAILABLE", "读取设备状态失败", 503);
        Map<String, Object> body = mapper.convertValue(result, MAP_TYPE);
        body.put("source", "local");
        return body;
    }

    /** Keeps state quality and nullable source times while projecting the task's device rows. */
    private static Map<String, Object> taskStatesView(Map<String, Object> body, List<String> deviceIds) {
        if (!(body.get("states") instanceof Map<?, ?> states)) return body;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String deviceId : deviceIds) rows.add(stateView(deviceId, states.get(deviceId)));
        Map<String, Object> view = new LinkedHashMap<>(body);
        view.put("states", rows);
        return view;
    }


    // ---------------------------------------------------------- management

    public TaskFact update(PrincipalInfo principal, String taskId, long expectedVersion, String payloadJson) {
        TaskFact current = ownedCurrent(principal, taskId, "office");
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试");
        if (terminalTask(current.status())) throw conflict("TASK_ALREADY_FINISHED", "任务已结束");
        JsonNode nextPayload = safePayload(payloadJson);
        if (!nextPayload.isObject()) throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效", 400);
        JsonNode beforePayload = safePayload(current.payloadJson());
        validatePlanStructure(nextPayload, beforePayload.toString());
        validateDurableHistory(beforePayload, nextPayload);
        validateFutureChange(beforePayload, nextPayload, "edit");
        validateDeviceReferences(beforePayload, nextPayload);
        String currentSource = source(beforePayload.path("source").asText("office"));
        assertCurrentCatalog(principal, currentSource, nextPayload.toString());
        ((com.fasterxml.jackson.databind.node.ObjectNode) nextPayload)
                .put("tenantCode", principal.tenantCode())
                .put("ownerKey", ownerKey(principal))
                .put("source", currentSource);
        attachFingerprints(principal, currentSource, nextPayload);
        String normalizedPayload = materializePreservingPhaseIds(nextPayload.toString(), current.payloadJson());
        return atomic.write(() -> {
            TaskFact replacement = new TaskFact(taskId, expectedVersion + 1,
                    "PAUSED".equals(current.status()) ? "PAUSED" : SCHEDULED,
                    normalizedPayload, current.idempotencyKey());
            TaskFact updated = tasks.updateIfVersion(replacement, expectedVersion)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务状态已变化，请刷新后再试"));
            cancelReplacedPhases(current, updated);
            scheduleIfDue(updated);
            return updated;
        });
    }

    public TaskFact cancel(PrincipalInfo principal, String taskId, long expectedVersion) {
        return cancel(principal, taskId, expectedVersion, "office");
    }

    public TaskFact cancel(PrincipalInfo principal, String taskId, long expectedVersion, String requestedSource) {
        // Runtime cancelTask authorization: the tenant sees the task only when at
        // least one device stays in the authorized catalog, and full cancellation
        // requires the catalog to cover every device — never a creator-only gate.
        TaskFact current = current(principal, taskId, requestedSource);
        assertCancelScope(principal, source(requestedSource), current);
        if (CANCELLED.equals(current.status())) return current;
        if (terminalTask(current.status())) throw conflict("TASK_ALREADY_FINISHED", "任务已结束");
        if (current.version() != expectedVersion) throw conflict("VERSION_CONFLICT", "任务已变化，请刷新后再试");
        return atomic.write(() -> cancelValidated(current));
    }

    /**
     * The one validated cancellation mutation shared by the direct cancel and the
     * confirmed cancellation proposal: task CAS, scheduled-phase cancellation,
     * terminal notification and protected-identity cleanup inside the caller's
     * transaction. Authorization and version checks stay outside so no external
     * call ever runs inside the write transaction.
     */
    private TaskFact cancelValidated(TaskFact current) {
        TaskFact cancelled = new TaskFact(current.taskId(), current.version() + 1, CANCELLED,
                current.payloadJson(), current.idempotencyKey());
        TaskFact result = tasks.updateIfVersion(cancelled, current.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务已变化，请刷新后再试"));
        cancelScheduledPhases(result);
        settleCancelledTask(result);
        return result;
    }

    /**
     * Runtime reconcileTask after cancellation: the terminal result reaches the
     * creator's inbox once, transactionally with the cancellation itself. A task
     * with an in-flight phase settles later under the scanner, which keeps the
     * cancelled fact authoritative instead of aggregating completed/partial.
     */
    private void settleCancelledTask(TaskFact cancelled) {
        if (phases == null || !phases.allTerminal(cancelled.taskId())) return;
        recordCancellationNotification(cancelled);
        if (executionIdentities != null) executionIdentities.deleteForTask(cancelled.taskId());
    }

    /** Runtime cancelTask visibility: no visible device hides the task, partial coverage is 403. */
    private void assertCancelScope(PrincipalInfo principal, String normalizedSource, TaskFact task) {
        JsonNode payload = readJson(task.payloadJson());
        Set<String> devices = deviceReferences(payload);
        if (devices.isEmpty()) return;
        Set<String> allowed = freshScope(principal, normalizedSource).deviceIds();
        if (devices.stream().noneMatch(allowed::contains)) {
            throw notFound("TASK_NOT_FOUND", "任务不存在");
        }
        if (!allowed.containsAll(devices)) {
            throw new BusinessException("TASK_FORBIDDEN", "当前授权不覆盖任务全部设备", 403);
        }
    }

    /**
     * Runtime reconcileTask after cancellation: the terminal result reaches the
     * inbox once, transactionally with the cancellation itself.
     */
    private void recordCancellationNotification(TaskFact cancelled) {
        JsonNode payload = readJson(cancelled.payloadJson());
        String tenant = payload.path("tenantCode").asText("");
        String recipient = payload.path("ownerKey").asText("");
        String taskSource = payload.path("source").asText("office");
        if (tenant.isBlank() || recipient.isBlank()) return;
        // Runtime reconcileTask records inside the task transaction: a failing
        // inbox write rolls the cancellation back instead of faking success.
        notifications.record(new TaskNotificationFact(
                UUID.nameUUIDFromBytes((cancelled.taskId() + "\u0000" + recipient)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                cancelled.taskId(), tenant, recipient, taskSource, CANCELLED,
                clock.instant(), null, "{\"outcome\":\"CANCELLED\"}"));
    }

    public TaskPlanFact plan(PrincipalInfo principal, String planId) {
        return plan(principal, planId, "office");
    }

    /** Owner-scoped plan fact used by management CAS paths; reads use {@link #planView}. */
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

    /**
     * Runtime POST /task-plans/:id/status: pause/resume with canPause/canResume
     * authorization, resume confirmation, idempotent replay and state_changed
     * recovery carrying the freshest plan view.
     */
    public Map<String, Object> updatePlan(PrincipalInfo principal, String planId, long expectedVersion,
                                          String operation, boolean confirmed, String requestedSource) {
        String normalizedSource = source(requestedSource);
        String requested = operation == null ? "" : operation.trim().toLowerCase();
        if (expectedVersion < 1 || !Set.of("pause", "resume").contains(requested)) {
            throw new BusinessException("INVALID_PLAN_STATUS", "请刷新计划后再选择启用状态", 400);
        }
        if ("resume".equals(requested) && !confirmed) {
            throw new BusinessException("RESUME_CONFIRMATION_REQUIRED", "重新启用周期计划需要确认当前范围和控制绑定", 400);
        }
        TaskPlanFact current = plan(principal, planId, normalizedSource);
        TaskReadScope scope = freshScope(principal, normalizedSource);
        Map<String, Object> view = planView(principal, planId, normalizedSource, scope);
        String desired = "pause".equals(requested) ? "PAUSED" : "ENABLED";
        if (current.version() == expectedVersion + 1 && desired.equals(current.status())
                && Boolean.TRUE.equals(view.get("canManage"))) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("plan", view);
            result.put("recovered", true);
            return result;
        }
        if (current.version() != expectedVersion) {
            throw new BusinessException("VERSION_CONFLICT", "计划已变化，已读取最新内容，请核对后再次确认",
                    Map.of("kind", "state_changed", "plan", view), 409);
        }
        if ("pause".equals(requested)) {
            if (!Boolean.TRUE.equals(view.get("canPause"))) {
                throw new BusinessException("PLAN_FORBIDDEN", "当前账号不能停用这个周期计划", 403);
            }
            atomic.write(() -> plans.updateIfVersion(new TaskPlanFact(planId, expectedVersion + 1, "PAUSED",
                    current.payloadJson(), current.idempotencyKey()), expectedVersion))
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请刷新后重试"));
        } else {
            if (!Boolean.TRUE.equals(view.get("canResume"))) {
                throw new BusinessException("PLAN_FORBIDDEN", "当前账号不能重新启用这个周期计划", 403);
            }
            assertCurrentCatalog(principal, normalizedSource, current.payloadJson(), true);
            if (!projector.allTargetsVisible(planSource(current), scope)) {
                throw conflict("PLAN_SCOPE_CHANGED", "计划目标范围或设备能力已变化，请重新确认");
            }
            atomic.write(() -> {
                plans.updateIfVersion(new TaskPlanFact(planId, expectedVersion + 1, "ENABLED",
                                current.payloadJson(), current.idempotencyKey()), expectedVersion)
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "计划已变化，请刷新后重试"));
                // Runtime plans.manage resume: the confirming actor's fresh
                // credential replaces the stored plan identity in the same CAS
                // transaction, so later occurrences never reuse a stale token.
                if (executionIdentities != null) executionIdentities.saveForPlan(planId, identity(principal));
                return null;
            });
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", planView(principal, planId, normalizedSource, scope));
        return result;
    }

    /**
     * Runtime-compatible direct plan cancellation. Occurrence cancellation is an
     * override/skip fact; all cancellation uses the store's transactional plan
     * CAS and never rewrites terminal history.
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
        Map<String, Object> view = planView(principal, planId, normalizedSource,
                freshScope(principal, normalizedSource));
        assertCurrentCatalog(principal, normalizedSource, current.payloadJson(), true);
        if ("all".equals(scope) && "DELETED".equals(current.status()) && current.version() == expectedVersion + 1
                && Boolean.TRUE.equals(view.get("canManage"))) {
            return cancellationResult(current, scope);
        }
        if ("occurrence".equals(scope) && current.version() == expectedVersion + 1) {
            try {
                TaskPlanOccurrence existing = plans.findOccurrence(planId, date).orElse(null);
                if (existing != null && existing.skipped() && existing.planVersion() == current.version()) {
                    return cancellationResult(current, scope);
                }
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生取消能力", 503);
            }
        }
        if (current.version() != expectedVersion) {
            throw new BusinessException("VERSION_CONFLICT", "安排已变化，已核实当前状态；原取消范围未执行，请核对后重新确认",
                    Map.of("kind", "state_changed", "scope", scope, "plan", view), 409);
        }
        if (!Boolean.TRUE.equals(view.get("canManage"))) {
            throw new BusinessException("TASK_FORBIDDEN", "当前无权取消整个计划", 403);
        }
        TaskPlanFact changed;
        if ("occurrence".equals(scope)) {
            try {
                atomic.write(() -> plans.overrideOccurrenceIfVersion(planId, date, expectedVersion, null, clock.instant()))
                        .orElseThrow(() -> conflict("VERSION_CONFLICT", "安排已变化，请刷新后重试"));
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_OCCURRENCE_UNSUPPORTED", "当前存储未提供本次发生取消能力", 503);
            }
            changed = plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在"));
        } else {
            try {
                changed = atomic.write(() -> {
                    // Runtime store.cancelPlan: the still-scheduled/executing
                    // materialized tasks cancel inside the plan's transaction;
                    // creators are notified and credentials dropped for the ones
                    // without in-flight work, in-flight ones settle under the scanner.
                    List<TaskFact> materialized = materializedPlanTasks(planId);
                    TaskPlanFact deleted = plans.cancelAllIfVersion(planId, expectedVersion, clock.instant())
                            .orElseThrow(() -> conflict("VERSION_CONFLICT", "安排已变化，请刷新后重试"));
                    for (TaskFact task : materialized) settleCancelledTask(task);
                    return deleted;
                });
            } catch (UnsupportedOperationException unavailable) {
                throw new BusinessException("TASK_PLAN_CANCELLATION_UNAVAILABLE", "当前存储未提供计划取消能力", 503);
            }
        }
        return cancellationResult(changed, scope);
    }

    private Map<String, Object> cancellationResult(TaskPlanFact plan, String scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", plan.planId());
        result.put("version", plan.version());
        result.put("status", "all".equals(scope) ? "deleted" : "skipped");
        result.put("scope", scope);
        return result;
    }

    /** Runtime store.cancelPlan scope: materialized occurrence tasks still scheduled or executing. */
    private List<TaskFact> materializedPlanTasks(String planId) {
        List<TaskFact> active = new ArrayList<>();
        for (TaskPlanOccurrence occurrence : plans.occurrences(planId)) {
            String taskId = occurrence.taskId();
            if (taskId == null) continue;
            TaskFact task = tasks.find(taskId).orElse(null);
            if (task == null) continue;
            if (SCHEDULED.equals(task.status()) || "EXECUTING".equals(task.status())) active.add(task);
        }
        return active;
    }

    // -------------------------------------------------------------- plans

    /** Tenant-visible plan list (Runtime plans.list + describe), trimmed by scope. */
    public List<Map<String, Object>> plans(PrincipalInfo principal, String source) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        List<Map<String, Object>> views = new ArrayList<>();
        TaskReadScope scope = null;
        for (TaskPlanFact plan : plans.listByTenant(principal.tenantCode(), normalizedSource)) {
            if ("DELETED".equalsIgnoreCase(plan.status())) continue;
            JsonNode payload = readJson(plan.payloadJson());
            if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) continue;
            if (scope == null) scope = scope(principal, normalizedSource);
            Map<String, Object> view = describePlan(principal, plan, normalizedSource, scope);
            if (view != null) views.add(view);
        }
        return views;
    }

    /** Runtime GET /task-plans/:id: describe view plus latest materialized task. */
    public Map<String, Object> planView(PrincipalInfo principal, String planId, String requestedSource) {
        return planView(principal, planId, requestedSource, null);
    }

    private Map<String, Object> planView(PrincipalInfo principal, String planId, String requestedSource,
                                         TaskReadScope scope) {
        checkPrincipal(principal);
        String normalizedSource = source(requestedSource);
        TaskPlanFact plan = plans.find(planId).orElseThrow(() -> notFound("PLAN_NOT_FOUND", "计划不存在"));
        JsonNode payload = readJson(plan.payloadJson());
        if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) {
            throw notFound("PLAN_NOT_FOUND", "计划不存在");
        }
        if (!normalizedSource.equals(source(payload.path("source").asText("office")))) {
            throw notFound("PLAN_NOT_FOUND", "计划不存在");
        }
        Map<String, Object> view = describePlan(principal, plan, normalizedSource,
                scope == null ? scope(principal, normalizedSource) : scope);
        if (view == null) throw notFound("PLAN_NOT_FOUND", "计划不存在或当前范围不可见");
        return view;
    }

    private Map<String, Object> describePlan(PrincipalInfo principal, TaskPlanFact plan, String normalizedSource,
                                            TaskReadScope scope) {
        PlanSource source = planSource(plan);
        Map<String, Object> latest = latestTaskView(principal, plan.planId(), normalizedSource, scope);
        Map<String, Object> view = projector.describe(source, scope, ownerKey(principal), latest);
        if (view != null && "PAUSED".equalsIgnoreCase(plan.status())) {
            String resumeNextAt = resumeNextAt(source);
            if (resumeNextAt != null) view.put("resumeNextAt", resumeNextAt);
        }
        return view;
    }

    private Map<String, Object> latestTaskView(PrincipalInfo principal, String planId,
                                               String normalizedSource, TaskReadScope scope) {
        try {
            String latestId = plans.latestTaskId(planId).orElse(null);
            if (latestId == null) return null;
            TaskFact task = tasks.find(latestId).orElse(null);
            if (task == null) return null;
            JsonNode payload = readJson(task.payloadJson());
            if (!principal.tenantCode().equals(payload.path("tenantCode").asText(""))) return null;
            return projector.detail(taskSource(task), scope, ownerKey(principal), planMetadata(payload));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** Runtime plans.resumeNextAt: the first future occurrence pointer of a paused plan. */
    private String resumeNextAt(PlanSource plan) {
        Instant anchor = projector.anchor(plan.payload());
        if (anchor == null) return null;
        LocalDate anchorDate = TaskRecurrence.localDate(anchor);
        TaskRecurrence.RepeatRule repeat;
        try {
            repeat = repeatRule(plan.payload().path("repeat"), anchorDate);
        } catch (RuntimeException invalid) {
            return null;
        }
        LocalDate date = TaskRecurrence.next(repeat, anchor, clock.instant());
        int examined = 0;
        while (date != null && examined++ < 4096) {
            TaskDetailProjector.OccurrenceState state = plan.occurrences().get(date);
            boolean taken = state != null && (state.taskId() != null
                    || Set.of("CLAIMED", "MATERIALIZED", "SKIPPED").contains(state.status().toUpperCase()));
            if (!taken) {
                JsonNode effective = projector.effectiveTemplate(plan, date);
                if (effective != null) {
                    Instant at = projector.anchor(effective);
                    if (at != null && at.isAfter(clock.instant())) return at.toString();
                }
                return TaskRecurrence.occurrenceAt(anchor, date).toString();
            }
            date = TaskRecurrence.next(repeat, anchor, TaskRecurrence.occurrenceAt(anchor, date), date);
        }
        return null;
    }

    // -------------------------------------------------------- notifications

    /**
     * Runtime GET /api/agent/notifications: stable (endedAt, id) cursor pagination,
     * visibility equal to the task read model and the notification projection with
     * label, reason and execution summary.
     */
    public Map<String, Object> notificationsView(PrincipalInfo principal, String source, int limit, String before) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        if (limit < 1 || limit > 100) throw new BusinessException("INVALID_LIMIT", "请选择有效分页大小", 400);
        TaskReadScope scope = scope(principal, normalizedSource);
        String tenant = principal.tenantCode();
        String recipient = ownerKey(principal);
        String cursor = null;
        if (before != null && !before.isBlank()) {
            TaskNotificationFact cursorFact = notifications.find(before)
                    .orElseThrow(() -> notFound("NOTIFICATION_NOT_FOUND", "消息不存在或已不可查看"));
            if (!tenant.equals(cursorFact.tenantCode()) || !recipient.equals(cursorFact.recipientKey())
                    || !normalizedSource.equals(source(cursorFact.source()))) {
                throw notFound("NOTIFICATION_NOT_FOUND", "消息不存在或已不可查看");
            }
            cursor = before;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        String lastVisibleId = null;
        boolean hasMore = false;
        scan:
        while (true) {
            List<TaskNotificationFact> page = notifications.list(tenant, recipient, normalizedSource, 60, cursor);
            for (TaskNotificationFact fact : page) {
                if (!notificationTaskVisible(fact.taskId(), scope)) continue;
                if (rows.size() == limit) {
                    hasMore = true;
                    break scan;
                }
                rows.add(notificationView(fact, principal, scope));
                lastVisibleId = fact.notificationId();
            }
            if (page.size() < 60) break;
            cursor = page.get(page.size() - 1).notificationId();
        }
        long unread = notifications.unread(tenant, recipient, normalizedSource).stream()
                .filter(fact -> notificationTaskVisible(fact.taskId(), scope)).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notifications", rows);
        result.put("unreadCount", unread);
        result.put("nextCursor", hasMore ? lastVisibleId : null);
        return result;
    }

    /** Runtime notification visibility: a visible phase device or the creator's recurring task. */
    private boolean notificationTaskVisible(String taskId, TaskReadScope scope) {
        TaskFact task = tasks.find(taskId).orElse(null);
        if (task == null) return false;
        JsonNode payload = readJson(task.payloadJson());
        if (!payload.path("sourcePlanId").asText("").isBlank()) return true;
        Set<String> references = deviceReferences(payload);
        return references.stream().anyMatch(scope.deviceIds()::contains);
    }

    private Map<String, Object> notificationView(TaskNotificationFact fact, PrincipalInfo principal, TaskReadScope scope) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", fact.notificationId());
        view.put("taskId", fact.taskId());
        view.put("source", fact.source());
        view.put("outcome", TaskDetailProjector.lowerStatus(fact.outcome()));
        view.put("endedAt", fact.endedAt().toString());
        view.put("readAt", fact.readAt() == null ? null : fact.readAt().toString());
        JsonNode payload = readJson(fact.payloadJson());
        if (payload.path("reason").isTextual() && !payload.path("reason").asText().isBlank()) {
            view.put("reason", payload.path("reason").asText());
        }
        TaskFact task = tasks.find(fact.taskId()).orElse(null);
        if (task != null) {
            JsonNode taskPayload = readJson(task.payloadJson());
            view.put("label", firstText(taskPayload, "label", "title", "objective"));
            try {
                Map<String, Object> detail = projector.detail(taskSource(task), scope, fact.recipientKey(),
                        planMetadata(taskPayload));
                if (detail != null && detail.get("report") instanceof Map<?, ?> report
                        && report.get("summary") != null) {
                    view.put("summary", report.get("summary"));
                }
            } catch (RuntimeException unavailable) {
                // The inbox row stays honest without the optional report projection.
            }
        }
        return view;
    }

    private static String firstText(JsonNode payload, String... fields) {
        for (String field : fields) {
            JsonNode value = payload.path(field);
            if (value.isTextual() && !value.asText().isBlank()) return value.asText();
        }
        return "";
    }

    public Map<String, Object> seenNotification(PrincipalInfo principal, String source, String id) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        TaskReadScope scope = scope(principal, normalizedSource);
        TaskNotificationFact fact = notifications.find(id)
                .orElseThrow(() -> notFound("NOTIFICATION_NOT_FOUND", "消息不存在或已不可查看"));
        if (!principal.tenantCode().equals(fact.tenantCode()) || !ownerKey(principal).equals(fact.recipientKey())
                || !normalizedSource.equals(source(fact.source()))
                || !notificationTaskVisible(fact.taskId(), scope)) {
            throw notFound("NOTIFICATION_NOT_FOUND", "消息不存在或已不可查看");
        }
        TaskNotificationFact seen = notifications.markSeen(principal.tenantCode(), ownerKey(principal),
                normalizedSource, id, clock.instant());
        return Map.of("id", seen.notificationId(),
                "readAt", seen.readAt() == null ? clock.instant().toString() : seen.readAt().toString());
    }

    public Map<String, Object> seenAllNotifications(PrincipalInfo principal, String source) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        TaskReadScope scope = scope(principal, normalizedSource);
        Instant at = clock.instant();
        List<String> visibleUnread = notifications.unread(principal.tenantCode(), ownerKey(principal), normalizedSource)
                .stream()
                .filter(fact -> notificationTaskVisible(fact.taskId(), scope))
                .map(TaskNotificationFact::notificationId)
                .toList();
        int marked = notifications.markAllSeen(principal.tenantCode(), ownerKey(principal), normalizedSource,
                visibleUnread, at);
        return Map.of("readAt", at.toString(), "marked", marked);
    }

    // ------------------------------------------------ conversation cards

    /**
     * Conversation card overlay (Runtime taskStore.proposal of a scheduled row):
     * a confirmed creation reports created=true with the real task/plan identity
     * and pointers — never the bare proposal CONFIRMED status.
     */
    @Override
    public Map<String, Object> conversationProposalState(PrincipalInfo principal, String proposalId, String requestedSource) {
        TaskProposalFact proposal = proposal(principal, proposalId, requestedSource);
        String normalizedSource = source(requestedSource);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", proposalView(proposal).get("status"));
        state.put("version", proposal.version());
        state.put("plannedAt", safePayload(proposal.payloadJson()).path("phases").findValuesAsText("at"));
        if (proposal.expiresAt() != null) state.put("expiresAt", proposal.expiresAt().toString());
        if ("TASK".equals(proposal.kind()) && CONFIRMED.equals(proposal.status())) {
            JsonNode payload = safePayload(proposal.payloadJson());
            if (payload.path("repeat").isObject()) {
                TaskPlanFact plan = plans.findByIdempotencyKey("plan:" + proposalId)
                        .orElseThrow(() -> conflict("TASK_CREATION_UNCONFIRMED", "任务登记结果尚未核实"));
                if (!owns(plan.payloadJson(), principal)) throw notFound("TASK_PLAN_NOT_FOUND", "任务计划不存在");
                assertSource(plan.payloadJson(), normalizedSource);
                PlanSource source = planSource(plan);
                state.put("taskId", proposalId);
                state.put("created", true);
                state.put("planId", plan.planId());
                state.put("planStatus", TaskDetailProjector.lowerStatus(plan.status()));
                state.put("status", "SCHEDULED");
                state.put("version", plan.version());
                if ("ENABLED".equalsIgnoreCase(plan.status())) {
                    if (source.nextAt() != null) state.put("nextAt", source.nextAt().toString());
                    if (source.nextDate() != null) {
                        state.put("nextDate", source.nextDate().toString());
                        state.put("nextOccurrenceId", TaskRecurrence.occurrenceId(plan.planId(), source.nextDate()));
                    }
                    state.put("nextCheckpointAt", projector.nextCheckpointAt(source));
                }
            } else {
                TaskFact task = tasks.findByIdempotencyKey("task:" + proposalId)
                        .orElseThrow(() -> conflict("TASK_CREATION_UNCONFIRMED", "任务登记结果尚未核实"));
                if (!owns(task.payloadJson(), principal)) throw notFound("TASK_NOT_FOUND", "任务不存在");
                assertSource(task.payloadJson(), normalizedSource);
                state.put("taskId", task.taskId());
                state.put("created", true);
                state.put("status", task.status());
                state.put("version", task.version());
            }
        }
        return state;
    }

    public Map<String, Object> proposalView(TaskProposalFact proposal) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("proposalId", proposal.proposalId()); view.put("version", proposal.version());
        boolean expired = PENDING.equals(proposal.status()) && proposal.expiresAt() != null
                && !clock.instant().isBefore(proposal.expiresAt());
        view.put("status", expired ? "EXPIRED" : proposal.status());
        if (proposal.expiresAt() != null) view.put("expiresAt", proposal.expiresAt().toString());
        if (!"TASK".equals(proposal.kind())) {
            view.put("kind", proposal.kind()); view.put("operation", proposal.operation());
            if (!"{}".equals(proposal.targetJson())) view.put("target", mapper.convertValue(publicPayload(safePayload(proposal.targetJson())), Object.class));
            if (!"{}".equals(proposal.beforeJson())) view.put("before", mapper.convertValue(publicPayload(safePayload(proposal.beforeJson())), Object.class));
            if (!"{}".equals(proposal.afterJson())) view.put("after", mapper.convertValue(publicPayload(safePayload(proposal.afterJson())), Object.class));
        }
        view.put("payload", mapper.convertValue(publicPayload(safePayload(proposal.payloadJson())), Object.class));
        JsonNode proposed = safePayload(proposal.payloadJson());
        if (proposed.path("repeat").isObject()) {
            try {
                Instant anchor = anchor(proposed);
                TaskRecurrence.RepeatRule repeat = repeatRule(proposed.path("repeat"), TaskRecurrence.localDate(anchor));
                java.time.LocalDate next = TaskRecurrence.next(repeat, anchor, clock.instant());
                if (next != null) {
                    view.put("nextOccurrenceId", TaskRecurrence.occurrenceId(proposal.proposalId(), next));
                    view.put("nextCheckpointAt", TaskRecurrence.occurrenceAt(anchor, next).toString());
                }
                Instant checkpoint = activeWindowCheckpoint(proposed, clock.instant());
                if (checkpoint != null) {
                    view.put("nextOccurrenceId", TaskRecurrence.occurrenceId(proposal.proposalId(),
                            TaskRecurrence.localDate(clock.instant())));
                    view.put("nextCheckpointAt", checkpoint.toString());
                }
            } catch (RuntimeException ignored) {
                // Invalid legacy proposals remain visible so the user can discard them.
            }
        }
        return view;
    }

    private Instant activeWindowCheckpoint(JsonNode payload, Instant now) {
        JsonNode interval = payload.path("interval");
        JsonNode phases = payload.path("phases");
        if (!interval.isObject() || !phases.isArray() || phases.size() <= 1) return null;
        try {
            Instant start = Instant.parse(interval.path("startsAt").asText());
            Instant end = Instant.parse(interval.path("endsAt").asText());
            if (start.isAfter(now) || !now.isBefore(end)) return null;
            Instant first = null;
            for (JsonNode phase : phases) {
                Instant at = Instant.parse(phase.path("at").asText());
                if (at.isAfter(now) && (first == null || at.isBefore(first))) first = at;
            }
            return first;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ scheduling

    private String materializePhaseIds(String payloadJson) {
        JsonNode parsed = safePayload(payloadJson);
        if (!parsed.isObject() || !parsed.path("phases").isArray()) return parsed.toString();
        com.fasterxml.jackson.databind.node.ObjectNode root = (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        TaskPhaseIds.assignMissing(root);
        return root.toString();
    }

    private void scheduleIfDue(TaskFact task) {
        if (!SCHEDULED.equals(task.status())) return;
        JsonNode payload = safePayload(task.payloadJson());
        JsonNode phases = payload.path("phases");
        if (!phases.isArray()) return;
        for (JsonNode phase : phases) {
            String phaseStatus = phase.path("status").asText("").trim().toUpperCase();
            // Only untouched or explicitly PENDING checkpoints are scheduled; recovery
            // outcomes like MISSED/UNKNOWN/EXECUTING are never re-armed.
            if (!phaseStatus.isEmpty() && !"PENDING".equals(phaseStatus)) continue;
            if (phase.path("phaseId").isTextual() && phase.path("at").isTextual()) {
                try { scheduler.schedule(task.taskId(), phase.path("phaseId").asText(), Instant.parse(phase.path("at").asText())); }
                catch (RuntimeException error) {
                    throw new BusinessException("TASK_SCHEDULER_UNAVAILABLE", "任务已记账，但调度服务暂不可用，请稍后重试", 503);
                }
            }
        }
    }

    private static boolean terminalTask(String status) {
        return status != null && Set.of("COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "MISSED", "CANCELLED")
                .contains(status.toUpperCase());
    }

    private void cancelScheduledPhases(TaskFact task) {
        JsonNode payloadPhases = safePayload(task.payloadJson()).path("phases");
        if (payloadPhases.isArray()) {
            for (JsonNode phase : payloadPhases) {
                String phaseStatus = phase.path("status").asText("").trim().toUpperCase();
                if (!phaseStatus.isEmpty() && !"PENDING".equals(phaseStatus)) continue;
                if (this.phases != null && phase.path("phaseId").isTextual()) {
                    try {
                        var durable = this.phases.find(phase.path("phaseId").asText());
                        if (durable.isPresent() && !"PENDING".equalsIgnoreCase(durable.get().status())) continue;
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

    private void cancelReplacedPhases(TaskFact before, TaskFact after) {
        java.util.LinkedHashSet<String> retained = new java.util.LinkedHashSet<>();
        for (JsonNode phase : safePayload(after.payloadJson()).path("phases")) {
            String id = phase.path("phaseId").asText("");
            if (!id.isBlank()) retained.add(id);
        }
        for (JsonNode phase : safePayload(before.payloadJson()).path("phases")) {
            String id = phase.path("phaseId").asText("");
            if (id.isBlank() || retained.contains(id)) continue;
            if (this.phases != null) {
                try {
                    var durable = this.phases.find(id);
                    if (durable.isPresent() && !"PENDING".equalsIgnoreCase(durable.get().status())) continue;
                } catch (RuntimeException unavailable) {
                    continue;
                }
            }
            try { scheduler.cancel(before.taskId(), id); } catch (RuntimeException ignored) { }
        }
    }

    // ------------------------------------------------ proposal management

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
            throw conflict(proposal.kind().equals("TASK") ? "TASK_EXPIRED"
                    : proposal.kind().equals("CHANGE") ? "CHANGE_EXPIRED" : "CANCELLATION_EXPIRED", "任务提案已过期，请重新提出");
        }
    }

    private void markConfirmed(TaskProposalFact proposal) {
        proposals.updateIfVersion(withStatus(proposal, CONFIRMED), proposal.version())
                .orElseThrow(() -> conflict("VERSION_CONFLICT", "任务提案已变化，请刷新后重试"));
    }

    /** Confirms and appends the audit fact in the same write transaction, so an audited
     * decision and its business fact commit together or not at all. */
    private void markConfirmed(PrincipalInfo principal, TaskProposalFact proposal) {
        markConfirmed(proposal);
        auditProposal(principal, proposal.source(), confirmedEventType(proposal.kind()), withStatus(proposal, CONFIRMED));
    }

    private static String confirmedEventType(String kind) {
        return switch (kind == null ? "" : kind) {
            case "CHANGE" -> "TASK_CHANGE_CONFIRMED";
            case "CANCELLATION" -> "TASK_CANCELLATION_CONFIRMED";
            default -> "TASK_PROPOSAL_CONFIRMED";
        };
    }

    /** Attributed audit fact for a member decision on a task proposal; audit failure fails the request. */
    private void auditProposal(PrincipalInfo principal, String source, String eventType, TaskProposalFact proposal) {
        if (audit == null) return;
        Map<String, Object> body = new LinkedHashMap<>();
        JsonNode payload = safePayload(proposal.payloadJson());
        body.put("source", source);
        if (principal.principalId() != null) body.put("principalId", principal.principalId());
        if (principal.identityRealm() != null) body.put("identityRealm", principal.identityRealm());
        if (principal.displayName() != null) body.put("displayName", principal.displayName());
        if (principal.mobileNumber() != null) body.put("mobileNumber", principal.mobileNumber());
        String conversationId = payload.path("conversationId").asText(null);
        if (conversationId != null) body.put("conversationId", conversationId);
        String turnId = payload.path("turnId").asText(null);
        if (turnId != null) body.put("turnId", turnId);
        body.put("proposalId", proposal.proposalId());
        body.put("kind", proposal.kind());
        String taskId = payload.path("taskId").asText(null);
        if (taskId != null) body.put("taskId", taskId);
        String label = payload.path("label").asText("");
        if (!label.isBlank()) body.put("label", label);
        body.put("status", proposal.status());
        body.put("revision", proposal.version());
        body.put("at", clock.instant().toString());
        try {
            audit.append(new AuditEventFact(UUID.randomUUID().toString(), principal.tenantCode(), eventType,
                    mapper.writeValueAsString(body)));
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BusinessException("AUDIT_UNAVAILABLE", "审计记录未能保存，请稍后重试", 503);
        }
    }

    private TaskProposalFact withStatus(TaskProposalFact proposal, String status) {
        return new TaskProposalFact(proposal.proposalId(), proposal.version() + 1, status, proposal.payloadJson(),
                proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(), proposal.beforeJson(), proposal.afterJson(),
                proposal.operation(), proposal.tenantCode(), proposal.ownerKey(), proposal.source(), proposal.expiresAt());
    }

    private boolean proposalOwned(TaskProposalFact proposal, PrincipalInfo principal, String expectedSource) {
        checkPrincipal(principal);
        JsonNode payload = readJson(proposal.payloadJson());
        String tenant = proposal.tenantCode().isBlank() ? payload.path("tenantCode").asText("") : proposal.tenantCode();
        String owner = proposal.ownerKey().isBlank() ? payload.path("ownerKey").asText("") : proposal.ownerKey();
        String storedSource = proposal.source().isBlank() ? payload.path("source").asText("") : proposal.source();
        return principal.tenantCode().equals(tenant) && ownerKey(principal).equals(owner)
                && source(expectedSource).equals(source(storedSource));
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

    private JsonNode taskPayloadForProposal(PrincipalInfo principal, String source, String id) {
        TaskFact task = current(principal, id, source);
        assertSource(task.payloadJson(), source);
        return safePayload(task.payloadJson());
    }

    private JsonNode planForProposal(PrincipalInfo principal, String source, String id) {
        TaskPlanFact plan = plan(principal, id, source);
        assertSource(plan.payloadJson(), source);
        return safePayload(plan.payloadJson());
    }

    private TaskRecurrence.OccurrenceReference occurrenceTarget(JsonNode target) {
        if (!"task".equals(target.path("type").asText())) return null;
        return TaskRecurrence.parseOccurrenceId(target.path("taskId").asText(""));
    }

    private JsonNode occurrenceForProposal(PrincipalInfo principal, String source,
                                           TaskRecurrence.OccurrenceReference occurrence) {
        TaskPlanFact plan = plan(principal, occurrence.planId(), source);
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
        String actual = source(readJson(payload).path("source").asText("office"));
        if (!actual.equals(source(expected))) throw notFound("TASK_NOT_FOUND", "任务不存在");
    }

    private JsonNode changedPayload(JsonNode before, JsonNode request, String operation) {
        JsonNode after = before.deepCopy();
        if ("edit".equals(operation) && request.path("template").isObject() && after.isObject()) {
            var template = request.path("template");
            var originalPhases = after.path("phases");
            var replacement = (com.fasterxml.jackson.databind.node.ObjectNode) after;
            replacement.setAll((com.fasterxml.jackson.databind.node.ObjectNode) template);
            if (template.has("phases")) replacement.set("phases", editPhases(originalPhases, template.path("phases")));
            // Both persisted name fields describe the reviewed replacement, never
            // a new label paired with the previous schedule's title.
            if (template.hasNonNull("label")) replacement.set("title", template.get("label"));
            else if (template.hasNonNull("title")) replacement.set("label", template.get("title"));
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
            for (String field : List.of("principalId", "identityRealm")) {
                result.remove(field);
                if (before.hasNonNull(field)) result.set(field, before.get(field));
            }
        }
        return TaskPayloadSafety.withoutCredentials(mapper, after);
    }

    private JsonNode editPhases(JsonNode original, JsonNode requested) {
        if (!requested.isArray()) return requested;
        com.fasterxml.jackson.databind.node.ArrayNode merged = null;
        for (int index = 0; index < requested.size(); index++) {
            JsonNode patch = requested.get(index);
            if (patch.isObject() && patch.has("at") && (patch.size() == 1
                    || (patch.size() == 2 && patch.has("actions")
                            && original.isArray() && original.size() == requested.size()
                            && sameRepeatedActions(original.get(index).path("actions"), patch.path("actions"))))) {
                if (!original.isArray() || original.size() != requested.size() || !original.get(index).isObject()) {
                    throw new BusinessException("INVALID_CHANGE", "仅改时间须保留原阶段数量和顺序；增删阶段须提交完整动作及读取要求", 400);
                }
                if (merged == null) {
                    merged = mapper.getNodeFactory().arrayNode(requested.size());
                    for (int previous = 0; previous < index; previous++) merged.add(requested.get(previous));
                }
                var phase = (com.fasterxml.jackson.databind.node.ObjectNode) original.get(index);
                phase.set("at", patch.get("at"));
                merged.add(phase);
            } else if (merged != null) {
                merged.add(patch);
            }
        }
        return merged == null ? requested : merged;
    }

    private boolean sameRepeatedActions(JsonNode original, JsonNode requested) {
        if (!original.isArray() || !requested.isArray() || original.size() != requested.size()) return false;
        for (int index = 0; index < original.size(); index++) {
            JsonNode action = original.get(index), candidate = requested.get(index), command = action.path("command");
            if (!candidate.isObject() || !action.path("deviceId").isTextual() || !action.path("kind").isTextual()
                    || !action.path("deviceId").equals(candidate.path("deviceId"))
                    || !action.path("kind").equals(candidate.path("kind"))
                    || !command.isObject() || command.isEmpty()) return false;
            if (candidate.has("command")) {
                if (!command.equals(candidate.get("command"))) return false;
            } else {
                var values = command.fields();
                while (values.hasNext()) {
                    var value = values.next();
                    if (!value.getValue().equals(candidate.get(value.getKey()))) return false;
                }
            }
            var fields = candidate.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().equals(action.get(field.getKey()))
                        && !field.getValue().equals(command.get(field.getKey()))) return false;
            }
        }
        return true;
    }

    /** Compare editable facts, not phase identities, execution state or equivalent timestamp notation. */
    private boolean sameEditDefinition(JsonNode before, JsonNode after) {
        if (!Objects.equals(text(before, "label", "title"), text(after, "label", "title"))
                || !before.path("repeat").equals(after.path("repeat"))
                || !sameAdditionalEditFields(before, after, EDIT_ROOT_SPECIAL_FIELDS)) return false;
        JsonNode oldInterval = before.path("interval"), newInterval = after.path("interval");
        if (!sameEditInstant(oldInterval.path("startsAt"), newInterval.path("startsAt"))
                || !sameEditInstant(oldInterval.path("endsAt"), newInterval.path("endsAt"))
                || !sameAdditionalEditFields(oldInterval, newInterval, EDIT_INTERVAL_TIME_FIELDS)) return false;
        JsonNode oldPhases = before.path("phases"), newPhases = after.path("phases");
        if (oldPhases.size() != newPhases.size()) return false;
        for (int index = 0; index < oldPhases.size(); index++) {
            JsonNode oldPhase = oldPhases.get(index), newPhase = newPhases.get(index);
            if (!sameEditInstant(oldPhase.path("at"), newPhase.path("at"))
                    || !oldPhase.path("actions").equals(newPhase.path("actions"))
                    || !oldPhase.path("read").equals(newPhase.path("read"))
                    || !sameAdditionalEditFields(oldPhase, newPhase, EDIT_PHASE_SPECIAL_FIELDS)) return false;
        }
        return true;
    }

    private boolean sameAdditionalEditFields(JsonNode before, JsonNode after, Set<String> excluded) {
        int compared = 0;
        var fields = before.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (excluded.contains(field.getKey())) continue;
            if (!field.getValue().equals(after.get(field.getKey()))) return false;
            compared++;
        }
        var names = after.fieldNames();
        while (names.hasNext()) if (!excluded.contains(names.next())) compared--;
        return compared == 0;
    }

    private boolean sameEditInstant(JsonNode before, JsonNode after) {
        if (before.equals(after)) return true;
        if (!before.isTextual() || !after.isTextual()) return false;
        try { return Instant.parse(before.asText()).equals(Instant.parse(after.asText())); }
        catch (RuntimeException invalid) { return false; }
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
        JsonNode payload = safePayload(payloadJson);
        String expectedCatalog = text(payload, "catalogFingerprint", "catalog_fingerprint", "deviceCatalogFingerprint");
        String expectedBinding = text(payload, "bindingFingerprint", "binding_fingerprint", "deviceBindingFingerprint");
        if (deviceReferences(payload).isEmpty() && expectedCatalog == null && expectedBinding == null) return;
        if (iot == null) {
            if (required) throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
            return;
        }
        IotSourceEnum requested;
        try {
            requested = IotSourceEnum.parse(source);
        } catch (RuntimeException invalid) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
        AuthorizedDeviceScope scope;
        try {
            scope = readCatalog.fresh(new RequestIdentity(
                    principal.authorization(), principal.tenantCode(), requested, principal.ownerKey()));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        if (scope == null || scope.source() != requested || scope.devices() == null) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
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
            JsonNode previous = i < original.size() ? original.get(i) : null;
            boolean unchanged = previous != null && previous.isObject()
                    && withoutPhaseId(previous).equals(withoutPhaseId(phase));
            String previousId = previous == null ? "" : previous.path("phaseId").asText("");
            if (unchanged && !previousId.isBlank()) {
                phase.put("phaseId", previousId);
            } else {
                phase.put("phaseId", UUID.randomUUID().toString());
            }
        }
        return after.toString();
    }

    private JsonNode withoutPhaseId(JsonNode value) {
        JsonNode copy = value.deepCopy();
        if (copy instanceof com.fasterxml.jackson.databind.node.ObjectNode object) object.remove("phaseId");
        return copy;
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

    // ------------------------------------------------------------ scope

    /** Authorized read scope from the backend projection, never a request-time upstream crawl. */
    @Override
    public TaskReadScope scope(PrincipalInfo principal, String source) {
        checkPrincipal(principal);
        if (iot == null) return TaskReadScope.empty();
        return readCatalog.read(scopeIdentity(principal, source));
    }

    @Override
    public TaskReadScope loadScope(RequestIdentity identity) {
        checkPrincipal(new PrincipalInfo(identity.authorization(), identity.tenantCode()));
        if (iot == null) return TaskReadScope.empty();
        return readCatalog.load(identity);
    }

    private TaskReadScope freshScope(PrincipalInfo principal, String source) {
        checkPrincipal(principal);
        if (iot == null) return TaskReadScope.empty();
        return TaskReadScope.of(readCatalog.fresh(scopeIdentity(principal, source)));
    }

    private RequestIdentity scopeIdentity(PrincipalInfo principal, String source) {
        IotSourceEnum requested;
        try { requested = IotSourceEnum.parse(source(source)); }
        catch (RuntimeException invalid) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400); }
        return new RequestIdentity(
                principal.authorization(), principal.tenantCode(), requested, ownerKey(principal));
    }

    private void attachFingerprints(PrincipalInfo principal, String source, JsonNode payload) {
        if (iot == null || deviceReferences(payload).isEmpty()) return;
        IotSourceEnum requested;
        try { requested = IotSourceEnum.parse(source); }
        catch (RuntimeException invalid) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400); }
        AuthorizedDeviceScope scope;
        try {
            scope = readCatalog.fresh(new RequestIdentity(
                    principal.authorization(), principal.tenantCode(), requested, ownerKey(principal)));
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        if (scope == null || scope.source() != requested) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        if (payload.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) payload)
                .put("catalogFingerprint", scope.catalogFingerprint())
                .put("bindingFingerprint", scope.bindingFingerprint());
    }

    // ------------------------------------------------------------ plumbing

    private JsonNode safePayload(String value) {
        if (value == null || value.isBlank()) throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        return TaskPayloadSafety.withoutCredentials(mapper, readJson(value));
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效"); }
    }

    private JsonNode publicPayload(JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        JsonNode safe = TaskPayloadSafety.withoutCredentials(mapper, value);
        if (!safe.isObject()) return safe;
        com.fasterxml.jackson.databind.node.ObjectNode result = (com.fasterxml.jackson.databind.node.ObjectNode) safe;
        result.remove("tenantCode");
        result.remove("ownerKey");
        return result;
    }

    private Object publicValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        return mapper.convertValue(publicPayload(value), Object.class);
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

    private boolean owns(String payload, PrincipalInfo principal) {
        JsonNode value = readJson(payload);
        return principal.tenantCode().equals(value.path("tenantCode").asText())
                && ownerKey(principal).equals(value.path("ownerKey").asText());
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
        return ScopedKey.of(principal.tenantCode(), ownerKey(principal), key);
    }

    public static String ownerKey(PrincipalInfo principal) {
        return principal.ownerKey();
    }
}
