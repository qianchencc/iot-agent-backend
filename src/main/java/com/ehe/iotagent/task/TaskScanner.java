package com.ehe.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventStore;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.report.ReportFactProducer;

/**
 * Recovery-safe scanner. A phase must be claimed by durable CAS before gateway
 * dispatch; executing phases are never retried after a restart. Terminal
 * aggregation, reports, notifications and protected-identity cleanup follow the
 * Runtime's reconcileTask semantics, including MISSED/SATISFIED outcomes and
 * recurring-plan invalidation when authorization or control bindings changed.
 */
public final class TaskScanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Runtime missPhase tolerance: a one-off checkpoint older than this is missed, never dispatched. */
    private static final Duration CATCH_UP_TOLERANCE = Duration.ofMinutes(10);
    private final TaskPhaseStore phases;
    private final TaskFactStore tasks;
    private final TaskExecutionGateway gateway;
    private final TaskNotificationStore notifications;
    private final ReportFactProducer reports;
    private final TaskReportCheckpointComposer reportComposer;
    private final TaskReportRetryStore reportRetries;
    private final TaskNotificationRetryStore notificationRetries;
    private final TaskExecutionIdentityStore executionIdentities;
    private final TaskPlanStore plans;
    private final TaskAtomicTransactions atomic;
    private final AuditEventStore audit;
    private final Clock clock;
    private volatile boolean stopping;
    /** Runtime migration of the scanner's concurrent window: six independently running tasks. */
    private static final int TASK_CONCURRENCY = 6;
    /** While executions hold the window open, interval transitions are swept at most this often. */
    private static final long SWEEP_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** Marks an in-flight scan so stopAndDrain can wait for real drain, like the Runtime's active promise. */
    private final AtomicBoolean scanning = new AtomicBoolean();
    /** Guarded by this monitor; only gateway execution, never terminal persistence, is interruptible. */
    private final Set<Thread> activeGateways = new HashSet<>();

    /** Canonical construction: every durable and optional seam is explicit, including the system audit sink. */
    public TaskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                       TaskNotificationStore notifications, ReportFactProducer reports,
                       TaskReportCheckpointComposer reportComposer, TaskReportRetryStore reportRetries,
                       TaskNotificationRetryStore notificationRetries, TaskExecutionIdentityStore executionIdentities,
                       TaskPlanStore plans, TaskAtomicTransactions atomic, AuditEventStore audit, Clock clock) {
        this.phases = Objects.requireNonNull(phases, "phases");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.notifications = Objects.requireNonNull(notifications, "notifications");
        this.reports = reports;
        this.reportComposer = reportComposer;
        this.reportRetries = reportRetries;
        this.notificationRetries = notificationRetries;
        this.executionIdentities = executionIdentities;
        this.plans = plans;
        this.atomic = atomic == null ? TaskAtomicTransactions.direct() : atomic;
        this.audit = audit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScanResult scanOnce() {
        if (!scanning.compareAndSet(false, true)) return new ScanResult(0, 0, 0, 0);
        try {
            return doScan();
        } finally {
            scanning.set(false);
            synchronized (this) {
                notifyAll();
            }
        }
    }

    /**
     * Rolling six-task window: independent due tasks each run their own serial
     * claim-execute-terminal chain, and every completion immediately frees its
     * slot for the next due task instead of waiting for the slowest peer. While
     * executions hold the window open the loop keeps sweeping interval starts
     * and ready terminal parents at a bounded sub-second-to-second cadence. A
     * stop request or an interrupted caller only stops new claims; claimed
     * phases always reach their terminal facts before this returns.
     */
    private ScanResult doScan() {
        int attempted = 0, claimed = 0, completed = 0, interrupted = 0;
        boolean interruptedDuringDrain = false;
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        AtomicBoolean draining = new AtomicBoolean();
        Set<String> runningTasks = new HashSet<>();
        Set<String> unclaimableTasks = new HashSet<>();
        Set<String> reconciledTasks = new HashSet<>();
        BlockingQueue<WorkerOutcome> finishedTasks = new ArrayBlockingQueue<>(TASK_CONCURRENCY);
        long lastSweepNanos = sweepIntervalTransitions(reconciledTasks, runningTasks, 0L);
        while (true) {
            if (!stopping && !draining.get()) {
                try {
                    attempted += startDueTasks(runningTasks, unclaimableTasks, finishedTasks, draining, failure);
                } catch (RuntimeException unavailable) {
                    failure.compareAndSet(null, unavailable);
                    draining.set(true);
                }
            }
            if (runningTasks.isEmpty()) break;
            lastSweepNanos = sweepIntervalTransitions(reconciledTasks, runningTasks, lastSweepNanos);
            WorkerOutcome outcome = null;
            try {
                outcome = finishedTasks.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interruptedDuringDrain = true;
                draining.set(true);
                interruptGateways();
            }
            if (outcome != null) {
                runningTasks.remove(outcome.taskId());
                if (outcome.claimed() == 0 && outcome.completed() == 0 && outcome.interrupted() == 0) {
                    unclaimableTasks.add(outcome.taskId());
                }
                claimed += outcome.claimed();
                completed += outcome.completed();
                interrupted += outcome.interrupted();
            }
        }
        sweepIntervalTransitions(reconciledTasks, runningTasks, 0L);
        retryNotifications();
        if (failure.get() != null) {
            if (interruptedDuringDrain) Thread.currentThread().interrupt();
            throw failure.get();
        }
        if (interruptedDuringDrain) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务阶段扫描等待被中断");
        }
        return new ScanResult(attempted, claimed, completed, interrupted);
    }

    /**
     * Fills every free slot of the window: one fetch of due phases, grouped per
     * task, starts one serial worker per task that is not already running and
     * not known-unclaimable from this scan. Returns the phases handed to workers.
     */
    private int startDueTasks(Set<String> runningTasks, Set<String> unclaimableTasks,
                              BlockingQueue<WorkerOutcome> finishedTasks,
                              AtomicBoolean draining, AtomicReference<RuntimeException> failure) {
        if (runningTasks.size() >= TASK_CONCURRENCY) return 0;
        List<TaskPhaseStore.TaskPhase> due = phases.due(clock.instant(), TASK_CONCURRENCY);
        if (due.isEmpty()) return 0;
        Map<String, List<TaskPhaseStore.TaskPhase>> byTask = new LinkedHashMap<>();
        for (TaskPhaseStore.TaskPhase phase : due) {
            byTask.computeIfAbsent(phase.taskId(), ignored -> new ArrayList<>()).add(phase);
        }
        int attempted = 0;
        for (Map.Entry<String, List<TaskPhaseStore.TaskPhase>> entry : byTask.entrySet()) {
            if (runningTasks.size() >= TASK_CONCURRENCY || stopping || draining.get()) break;
            String taskId = entry.getKey();
            if (runningTasks.contains(taskId) || unclaimableTasks.contains(taskId)) continue;
            List<TaskPhaseStore.TaskPhase> chain = entry.getValue();
            runningTasks.add(taskId);
            attempted += chain.size();
            Thread.ofVirtual().name("task-phase-scanner").unstarted(() -> {
                WorkerOutcome outcome = new WorkerOutcome(taskId, 0, 0, 0);
                try {
                    int[] counts = runTaskPhases(chain, draining);
                    outcome = new WorkerOutcome(taskId, counts[0], counts[1], counts[2]);
                } catch (RuntimeException error) {
                    failure.compareAndSet(null, error);
                    draining.set(true);
                } finally {
                    finishedTasks.add(outcome);
                }
            }).start();
        }
        return attempted;
    }

    private int[] runTaskPhases(List<TaskPhaseStore.TaskPhase> taskPhases, AtomicBoolean draining) {
        int claimed = 0, completed = 0, interrupted = 0;
        for (TaskPhaseStore.TaskPhase phase : taskPhases) {
            // Runtime processPhase returns immediately once stopping: no new
            // phase is claimed, and in-flight phases finish before drain.
            if (stopping || draining.get()) break;
            TaskFact task = tasks.find(phase.taskId()).orElse(null);
            if (task != null && beyondCatchUpWindow(task, phase) && phases.miss(
                    phase.phaseId(), clock.instant(), "已超过 10 分钟补执行窗口")) {
                completed++;
                auditCheckpointOutcome(phase, task, "MISSED", "已超过 10 分钟补执行窗口", false, null);
                if (phases.allTerminal(phase.taskId())) {
                    finalizeTerminal(phase, task, "MISSED",
                            "{\"outcome\":\"missed\",\"reason\":\"已超过 10 分钟补执行窗口\"}", null);
                }
                continue;
            }
            if (!phases.claim(phase.phaseId(), phase.version(), clock.instant())) continue;
            claimed++;
            if (task == null || !dispatchable(task.status())) {
                if (phases.finish(phase.phaseId(), phase.version() + 1, "CANCELLED",
                        "{\"outcome\":\"cancelled\",\"reason\":\"task_not_dispatchable\"}", clock.instant())) {
                    auditCheckpointOutcome(phase, task, "CANCELLED", "task_not_dispatchable", false, null);
                }
                continue;
            }
            // A claimed phase means execution began, so an observer of the
            // in-flight phase must see the parent as EXECUTING, never SCHEDULED.
            // The JDBC claim already promotes in its own transaction; this CAS
            // covers in-memory ports and is idempotent when the fact moved on.
            promoteToExecuting(task, false);
            try {
                TaskExecutionGateway.TaskExecutionResult result = executePhase(phase, task, draining);
                boolean finished = phases.finish(phase.phaseId(), phase.version() + 1, result.outcome(), result.payloadJson(), clock.instant());
                if (finished) {
                    completed++;
                    auditCheckpointOutcome(phase, task, result.outcome(), null, result.dispatchStarted(),
                            result.payloadJson());
                    if (reportComposer == null) terminalReport(task, phase, result);
                    JsonNode invalidation = read(result.payloadJson()).path("invalidation");
                    if (invalidation.isObject() && invalidation.path("kind").isTextual()) {
                        invalidateAfterExecution(task, invalidation.path("kind").asText(),
                                invalidation.path("reason").asText("任务固定范围已变化"));
                    }
                    if (phases.allTerminal(phase.taskId())) {
                        finalizeIfIntervalEnded(phase, task, result.outcome(), result.payloadJson(), null);
                    }
                }
            } catch (RuntimeException error) {
                // Dispatch may already have crossed the gateway boundary. Preserve unknown/interrupted;
                // do not roll back to pending and do not allow a second execution after restart.
                boolean dispatchStarted = !(error instanceof TaskExecutionGateway.DispatchException dispatch)
                        || dispatch.dispatchStarted();
                String status = dispatchStarted ? "INTERRUPTED" : "FAILED";
                String reason = error.getMessage() == null || error.getMessage().isBlank()
                        ? "not_dispatched" : error.getMessage();
                String payload = dispatchStarted
                        ? "{\"outcome\":\"unknown\"}"
                        : "{\"outcome\":\"failed\",\"reason\":" + quote(reason) + "}";
                String invalidationReason = error instanceof TaskExecutionGateway.DispatchException dispatch
                        && dispatch.reasonCode() != null ? dispatch.getMessage() : null;
                if (phases.finish(phase.phaseId(), phase.version() + 1, status, payload, clock.instant())) {
                    if (dispatchStarted) interrupted++;
                    auditCheckpointOutcome(phase, task, status, reason, dispatchStarted, null);
                    if (reportComposer == null) terminalReport(task, phase, new TaskExecutionGateway.TaskExecutionResult(status, payload, dispatchStarted));
                    if (invalidationReason != null) {
                        invalidateAfterExecution(task, invalidationKind(error), invalidationReason);
                    }
                    if (phases.allTerminal(phase.taskId())) {
                        finalizeIfIntervalEnded(phase, task, status, payload, invalidationReason);
                    }
                }
            }
        }
        return new int[] {claimed, completed, interrupted};
    }

    /** Runtime legacy_window: one-off tasks without interval and plan linkage keep only a 10-minute catch-up. */
    private boolean beyondCatchUpWindow(TaskFact task, TaskPhaseStore.TaskPhase phase) {
        JsonNode payload = read(task.payloadJson());
        if (payload.has("sourcePlanId") && payload.path("sourcePlanId").isTextual()) return false;
        JsonNode interval = payload.path("interval");
        if (interval.path("startsAt").isTextual() || interval.path("endsAt").isTextual()) return false;
        return phase.dueAt().isBefore(clock.instant().minus(CATCH_UP_TOLERANCE));
    }

    private TaskExecutionGateway.TaskExecutionResult executePhase(
            TaskPhaseStore.TaskPhase phase, TaskFact task, AtomicBoolean draining) {
        Thread worker = Thread.currentThread();
        synchronized (this) {
            if (stopping || draining.get()) throw new TaskExecutionGateway.DispatchException("任务执行已停止", false);
            activeGateways.add(worker);
        }
        try {
            return gateway.execute(new TaskExecutionGateway.TaskExecutionRequest(phase.taskId(), phase.phaseId(),
                    phase.source(), phase.payloadJson(), phase.dueAt(), task.payloadJson()));
        } finally {
            synchronized (this) {
                activeGateways.remove(worker);
                // The stop signal has been handled by the gateway. Persist its
                // settled/unknown result without interrupting the JDBC write.
                Thread.interrupted();
            }
        }
    }
    private synchronized void interruptGateways() {
        for (Thread worker : activeGateways) worker.interrupt();
    }

    /**
     * Runtime advanceIntervals, swept before due work and at most once per
     * second while executions hold the window open: interval starts promote,
     * and ready terminal parents finalize.
     */
    private long sweepIntervalTransitions(Set<String> reconciledTasks, Set<String> runningTasks, long lastSweepNanos) {
        if (lastSweepNanos != 0L && System.nanoTime() - lastSweepNanos < SWEEP_INTERVAL_NANOS) {
            return lastSweepNanos;
        }
        promoteStartedIntervals();
        reconcileFinishedTasks(reconciledTasks, runningTasks);
        return System.nanoTime();
    }

    /**
     * Runtime reconcileTask: an interval task stays executing until its window
     * ends even when every checkpoint already settled; the report and the
     * notification only exist after the interval matures.
     */
    private void finalizeIfIntervalEnded(TaskPhaseStore.TaskPhase phase, TaskFact task, String fallbackOutcome,
                                         String resultPayload, String annotatedReason) {
        TaskFact latest = tasks.find(task.taskId()).orElse(task);
        // Runtime reconcileTask keeps an already-cancelled fact cancelled; the
        // interval hold only applies to tasks that are still running.
        if (!isTerminal(latest.status())) {
            Instant endsAt = intervalEnd(latest.payloadJson());
            if (endsAt != null && clock.instant().isBefore(endsAt)) {
                // Runtime reconcileTask: the interval task is executing for its
                // whole window, even with every checkpoint already settled.
                promoteToExecuting(latest, true);
                return;
            }
        }
        finalizeTerminal(phase, task, fallbackOutcome, resultPayload, annotatedReason);
    }

    private static Instant intervalEnd(String payloadJson) {
        JsonNode endsAt = read(payloadJson).path("interval").path("endsAt");
        if (!endsAt.isTextual()) return null;
        try {
            return Instant.parse(endsAt.asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Instant intervalStart(String payloadJson) {
        JsonNode startsAt = read(payloadJson).path("interval").path("startsAt");
        if (!startsAt.isTextual()) return null;
        try {
            return Instant.parse(startsAt.asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * Idempotent task-fact CAS: a claimed phase or a started interval window
     * flips SCHEDULED to EXECUTING. The JDBC claim already promotes in its own
     * transaction, so this primarily covers in-memory ports and races.
     */
    private void promoteToExecuting(TaskFact task, boolean requireIntervalStart) {
        TaskFact current = tasks.find(task.taskId()).orElse(null);
        if (current == null || !"SCHEDULED".equals(current.status())) return;
        if (requireIntervalStart) {
            Instant startsAt = intervalStart(current.payloadJson());
            if (startsAt == null || clock.instant().isBefore(startsAt)) return;
        }
        try {
            tasks.updateIfVersion(new TaskFact(current.taskId(), current.version() + 1, "EXECUTING",
                    current.payloadJson(), current.idempotencyKey()), current.version());
        } catch (RuntimeException ignored) {
            // The next poll retries the promotion; phases stay claimable either way.
        }
    }

    /**
     * Runtime advanceIntervals start half: every sweep flips scheduled interval
     * tasks whose window began to EXECUTING, with or without due phases.
     */
    private void promoteStartedIntervals() {
        List<String> started;
        try { started = phases.startedIntervals(clock.instant(), 25); }
        catch (RuntimeException unavailable) { return; }
        for (String taskId : started) {
            try {
                TaskFact task = tasks.find(taskId).orElse(null);
                if (task != null) promoteToExecuting(task, true);
            } catch (RuntimeException ignored) {
                // The next sweep re-derives the candidate.
            }
        }
    }

    /**
     * Finalizes active parents whose phases are all terminal — interval tasks
     * only once their window ended, ordinary tasks (no interval end) as soon
     * as the store reports them ready. This repairs an interruption between
     * the last phase commit and the parent terminal transaction; a running
     * task owns its own finalization, and each candidate is attempted at most
     * once per scan so a failing terminal transaction never repeats reports.
     */
    private void reconcileFinishedTasks(Set<String> reconciledTasks, Set<String> runningTasks) {
        List<String> ready;
        try { ready = phases.readyToFinalize(clock.instant(), 25); }
        catch (RuntimeException unavailable) { return; }
        for (String taskId : ready) {
            if (runningTasks.contains(taskId)) continue;
            if (!reconciledTasks.add(taskId)) continue;
            try {
                TaskFact task = tasks.find(taskId).orElse(null);
                if (task == null || isTerminal(task.status())) continue;
                Instant endsAt = intervalEnd(task.payloadJson());
                if (endsAt != null && clock.instant().isBefore(endsAt)) continue;
                finalizeTerminal(null, task, task.status(), "{\"outcome\":\"interval_ended\"}", null);
            } catch (RuntimeException ignored) {
                // The next poll re-derives the candidate.
            }
        }
    }

    /**
     * Runtime disablePlanForPhase: pause the recurring plan, cancel this task's
     * still-pending phases, then close the task with the annotated reason so
     * the failure can not recur on the next occurrence.
     */
    private void invalidateAfterExecution(TaskFact task, String kind, String reason) {
        if (task == null || reason == null) return;
        JsonNode payload = read(task.payloadJson());
        if (!"company".equals(payload.path("source").asText(""))) return;
        String planId = payload.path("sourcePlanId").isTextual()
                ? payload.path("sourcePlanId").asText() : null;
        if (planId != null && plans != null) {
            try {
                plans.pauseForInvalidation(planId, reason, clock.instant());
            } catch (RuntimeException unavailable) {
                // The pause is retried on the next invalidation; the task-side
                // cancellation below already stops this occurrence.
            }
        }
        String annotation = (planId != null ? "周期计划已停用：" : "任务后续阶段已停止：") + reason;
        int cancelled = 0;
        try {
            cancelled = phases.cancelPending(task.taskId(), annotation, clock.instant());
        } catch (RuntimeException unavailable) {
            return;
        }
        if (cancelled > 0 && phases.allTerminal(task.taskId())) {
            TaskFact current = tasks.find(task.taskId()).orElse(null);
            if (current != null && !isTerminal(current.status())) {
                finalizeTerminal(null, current, "FAILED",
                        "{\"outcome\":\"failed\",\"reason\":" + quote(annotation) + "}", annotation);
            }
        }
    }

    private void finalizeTerminal(TaskPhaseStore.TaskPhase phase, TaskFact task, String fallbackOutcome,
                                  String resultPayload, String annotatedReason) {
        String outcome = aggregateOutcome(phases.outcomes(task.taskId()), fallbackOutcome);
        TerminalNotice notice = terminalNotice(outcome, task, annotatedReason);
        try {
            TaskFact terminalTask = atomic.write(() -> {
                TaskFact completed = completeTask(task.taskId(), fallbackOutcome, outcome);
                if (completed == null) return null;
                // An already-cancelled task keeps its durable result, not the phase aggregate.
                String payload = completed.status().equals(outcome)
                        ? notice.payload() : terminalNotice(completed.status(), task, annotatedReason).payload();
                persistTerminalNotification(completed,
                        field(task.payloadJson(), "source", phase == null ? "office" : phase.source()), payload);
                // The outcome fact and its audit row commit together; an audit
                // failure rolls the terminal transaction back so the next
                // readyToFinalize sweep republishes both instead of recording
                // an unaudited outcome.
                auditTaskOutcome(completed, annotatedReason);
                return completed;
            });
            if (terminalTask != null) {
                if (notificationRetries != null) {
                    try { notificationRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
                }
                // Report I/O belongs to the separate recovery poll. Keep only
                // credentials needed by that report; ordinary controls release theirs now.
                if (!notice.reportRequested() || "CANCELLED".equals(terminalTask.status())
                        || "MISSED".equals(terminalTask.status())) {
                    cleanupExecutionIdentity(task.taskId());
                }
            }
        } catch (RuntimeException transactionFailed) {
            // Task terminal and inbox row rolled back together; the next
            // readyToFinalize sweep re-derives both from the durable phase facts.
            if (notificationRetries != null) {
                try {
                    notificationRetries.markPending(task.taskId(),
                            annotatedReason == null ? "终态事务失败" : annotatedReason);
                } catch (RuntimeException ignored) {
                    // The LEFT JOIN recovery query re-derives the row without a queue entry.
                }
            }
        }
    }

    // ------------------------------------------------------------- system audit

    /**
     * Automatic execution is a system action attributed to the verified
     * creator binding stored by Java. Legacy tasks without that binding stay
     * unresolved. Audit outages never roll a settled phase back or redispatch;
     * the terminal transaction repairs its checkpoint audit from durable facts.
     */
    private void auditCheckpointOutcome(TaskPhaseStore.TaskPhase phase, TaskFact task, String status,
                                        String reason, Boolean dispatchStarted, String resultPayloadJson) {
        try {
            appendCheckpointAudit(phase, task, status, reason, dispatchStarted, resultPayloadJson, clock.instant());
        } catch (RuntimeException ignored) {
            // The parent terminal transaction re-derives this settled phase.
        }
    }

    private void appendCheckpointAudit(TaskPhaseStore.TaskPhase phase, TaskFact task, String status,
            String reason, Boolean dispatchStarted, String resultPayloadJson, Instant settledAt) {
        if (audit == null) return;
        String taskPayload = task == null ? "{}" : task.payloadJson();
        String tenant = field(taskPayload, "tenantCode", "");
        if (tenant.isBlank() || immediateDraft(taskPayload)) return;
        ObjectNodeBuilder body = new ObjectNodeBuilder();
        body.put("action", "execute_checkpoint");
        body.put("actor", "system");
        body.put("source", field(taskPayload, "source", phase.source()));
        body.put("taskId", phase.taskId());
        body.put("phaseId", phase.phaseId());
        storedCreatorFacts(body, read(taskPayload));
        body.put("status", status);
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        if (dispatchStarted != null) body.raw("dispatchStarted", dispatchStarted.toString());
        receiptReadbackProjection(body, resultPayloadJson);
        body.put("at", settledAt.toString());
        audit.append(new AuditEventFact(auditEventId(phase.taskId(), phase.phaseId(), "checkpoint"),
                tenant, "TASK_CHECKPOINT_EXECUTED", body.toString(), settledAt));
    }

    /**
     * Terminal task outcome joins the terminal write transaction: the outcome
     * fact, its inbox notification and the audit row commit together or not at
     * all. An append failure propagates so the transaction rolls back and the
     * next readyToFinalize sweep republishes all three; a duplicate
     * re-derivation appends the same deterministic event id without
     * overwriting the original content.
     */
    private void auditTaskOutcome(TaskFact terminal, String annotatedReason) {
        if (audit == null) return;
        String payload = terminal.payloadJson();
        String tenant = field(payload, "tenantCode", "");
        if (tenant.isBlank() || immediateDraft(payload)) return;
        for (TaskPhaseStore.PhaseResult row : phases.terminalPhases(terminal.taskId())) {
            TaskPhaseStore.TaskPhase phase = new TaskPhaseStore.TaskPhase(row.taskId(), row.phaseId(), 1,
                    field(payload, "source", "office"), row.definitionJson(), row.dueAt(), row.status());
            JsonNode result = read(row.resultJson());
            Boolean dispatched = result.path("dispatchStarted").isBoolean()
                    ? result.path("dispatchStarted").asBoolean() : null;
            appendCheckpointAudit(phase, terminal, row.status(), row.failureReason(), dispatched,
                    row.resultJson(), row.endedAt() == null ? clock.instant() : row.endedAt());
        }
        ObjectNodeBuilder body = new ObjectNodeBuilder();
        body.put("action", "record_outcome");
        body.put("actor", "system");
        body.put("source", field(payload, "source", "office"));
        body.put("taskId", terminal.taskId());
        storedCreatorFacts(body, read(payload));
        body.put("status", terminal.status());
        if (annotatedReason != null && !annotatedReason.isBlank()) body.put("reason", annotatedReason);
        body.put("endedAt", clock.instant().toString());
        body.put("at", clock.instant().toString());
        audit.append(new AuditEventFact(auditEventId(terminal.taskId(), "", "outcome"),
                tenant, "TASK_OUTCOME_RECORDED", body.toString(), clock.instant()));
    }

    /** Durable report fact produced by the system actor; best effort, the report row itself is authoritative. */
    private void auditReportProduced(TaskFact task, String phaseId, String reportId) {
        if (audit == null || reportId == null || reportId.isBlank()) return;
        try {
            String payload = task.payloadJson();
            String tenant = field(payload, "tenantCode", "");
            if (tenant.isBlank() || immediateDraft(payload)) return;
            ObjectNodeBuilder body = new ObjectNodeBuilder();
            body.put("action", "produce_report");
            body.put("actor", "system");
            body.put("source", field(payload, "source", "office"));
            body.put("taskId", task.taskId());
            body.put("phaseId", phaseId);
            body.put("reportId", reportId);
            storedCreatorFacts(body, read(payload));
            body.put("status", task.status());
            body.put("at", clock.instant().toString());
            audit.append(new AuditEventFact(auditEventId(task.taskId(), phaseId, "report"),
                    tenant, "TASK_REPORT_PRODUCED", body.toString(), clock.instant()));
        } catch (RuntimeException ignored) {
            // The durable report fact is authoritative; a failed audit append
            // never marks the report failed and never re-produces it.
        }
    }

    /** Deterministic event id per stable transition: retries, restarts and reconciliation re-append the same row. */
    private static String auditEventId(String taskId, String phaseId, String kind) {
        return UUID.nameUUIDFromBytes((taskId + "\u0000" + phaseId + "\u0000" + kind)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Server-stored creator binding only: conversation pointer, label, creator
     * display name and owner hash. A principal is copied verbatim when the
     * stored payload carries one; it is never derived, so absent facts stay
     * honestly unresolved instead of being reconstructed.
     */
    private static void storedCreatorFacts(ObjectNodeBuilder body, JsonNode taskPayload) {
        String conversationId = taskPayload.path("conversationId").asText(null);
        if (conversationId != null && !conversationId.isBlank()) body.put("conversationId", conversationId);
        String turnId = taskPayload.path("turnId").asText(null);
        if (turnId != null && !turnId.isBlank()) body.put("turnId", turnId);
        String label = taskPayload.path("label").asText("");
        if (!label.isBlank()) body.put("label", label);
        String creatorName = taskPayload.path("creatorName").asText("");
        if (!creatorName.isBlank()) body.put("creatorName", creatorName);
        String ownerKey = taskPayload.path("ownerKey").asText("");
        if (!ownerKey.isBlank()) body.put("ownerKey", ownerKey);
        String principalId = taskPayload.path("principalId").asText(null);
        if (principalId != null && !principalId.isBlank()) body.put("principalId", principalId);
        String identityRealm = taskPayload.path("identityRealm").asText(null);
        if (identityRealm != null && !identityRealm.isBlank()) body.put("identityRealm", identityRealm);
    }

    /**
     * Upstream receipt and platform readback stay separate facts (US9): one
     * receipt row per dispatched action plus the per-device readback state,
     * projected straight from the gateway's public device result. Read-only
     * checkpoints have no receipt facts and stay limited to the outcome counts.
     */
    private static void receiptReadbackProjection(ObjectNodeBuilder body, String resultPayloadJson) {
        JsonNode devices = read(resultPayloadJson).path("devices");
        if (!devices.isObject() || devices.isEmpty()) return;
        ArrayNode receipts = MAPPER.createArrayNode();
        ObjectNode readback = MAPPER.createObjectNode();
        ObjectNode counts = MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> rows = devices.fields();
        while (rows.hasNext()) {
            Map.Entry<String, JsonNode> row = rows.next();
            JsonNode device = row.getValue();
            ObjectNode receipt = receipts.addObject();
            receipt.put("actionId", device.path("deviceId").asText(row.getKey()));
            if (device.hasNonNull("receipt")) receipt.put("outcome", device.path("receipt").asText(""));
            String deviceReason = device.path("reason").asText("");
            if (!deviceReason.isBlank()) receipt.put("reason", deviceReason);
            if (device.hasNonNull("readback")) {
                readback.put(row.getKey(), device.path("readback").asText("unconfirmed"));
            }
            String outcome = device.path("outcome").asText("");
            if (!outcome.isBlank()) counts.put(outcome, counts.path(outcome).asLong(0) + 1);
        }
        counts.put("devices", devices.size());
        body.raw("counts", counts.toString());
        ObjectNode result = MAPPER.createObjectNode();
        result.set("receipts", receipts);
        result.set("readback", readback);
        body.raw("result", result.toString());
    }

    /** Builds the durable inbox payload: outcome, invalidation reason, honest AC summary when evidence exists. */
    private TerminalNotice terminalNotice(String outcome, TaskFact task, String annotatedReason) {
        ObjectNodeBuilder payload = new ObjectNodeBuilder();
        JsonNode taskPayload = read(task.payloadJson());
        boolean reportRequested = taskPayload.path("reportRequest").isObject();
        payload.put("outcome", outcome);
        if (annotatedReason != null) payload.put("reason", annotatedReason);
        if (reportComposer != null) {
            try {
                String summary = reportComposer.summarize(task, phases.terminalPhases(task.taskId()));
                if (summary != null) {
                    payload.raw("summary", summary);
                    reportRequested |= "company".equals(taskPayload.path("source").asText());
                }
            } catch (RuntimeException ignored) {
                // A missing summary must never block the terminal notification itself.
            }
        }
        return new TerminalNotice(payload.toString(), reportRequested && !immediateDraft(task.payloadJson()));
    }

    /**
     * Runtime notifications.record: only scheduled/recurring_plan tasks enter
     * the inbox; immediate drafts are action history, never task notifications.
     * A record failure propagates so the surrounding terminal transaction rolls
     * back together with the parent fact.
     */
    private void persistTerminalNotification(TaskFact task, String source, String notificationPayload) {
        String payload = task.payloadJson();
        if (immediateDraft(payload)) return;
        String tenant = field(payload, "tenantCode", "");
        String recipient = field(payload, "ownerKey", "");
        if (tenant.isBlank() || recipient.isBlank()) return;
        notifications.record(new TaskNotificationFact(
                notificationId(task.taskId(), recipient), task.taskId(), tenant, recipient, source,
                task.status(), clock.instant(), null, notificationPayload));
    }

    /** Durable notification compensation: rebuild the inbox row from terminal facts only. */
    private void recordTerminalNotification(TaskFact task, String source, String notificationPayload) {
        try {
            persistTerminalNotification(task, source, notificationPayload);
            if (notificationRetries != null) {
                try { notificationRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
            }
        } catch (RuntimeException failed) {
            // The terminal fact is durable; the inbox row is compensated from
            // the durable retry queue, never by redispatching the phase.
            if (notificationRetries != null) {
                try { notificationRetries.markPending(task.taskId(), "通知落库失败"); } catch (RuntimeException ignored) {
                    // The next terminal pass or restart re-derives the queue row.
                }
            }
        }
    }

    /** Separate background report work: never holds the phase clock or terminal publication. */
    public void recoverReports() {
        if (reportComposer == null || reportRetries == null) return;
        List<TaskFact> pending;
        try { pending = reportRetries.pending(4); }
        catch (RuntimeException unavailable) { return; }
        for (TaskFact task : pending) {
            try {
                if (!phases.allTerminal(task.taskId())) continue;
                // The execution outcome was aggregated at terminal time and is
                // never rewritten by recovery; the retry only rebuilds the report.
                produceTaskReport(task, task.status());
            } catch (RuntimeException ignored) {
                // Bounded retry state is recorded below; this loop must not stop phase scanning.
                try { reportRetries.failed(task.taskId(), "report retry failed"); } catch (RuntimeException ignoredAgain) { }
            }
        }
    }

    /** Durable notification compensation: rebuild the inbox row from terminal facts only. */
    private void retryNotifications() {
        if (notificationRetries == null) return;
        List<TaskFact> pending;
        try { pending = notificationRetries.pending(4); }
        catch (RuntimeException unavailable) { return; }
        for (TaskFact task : pending) {
            try {
                if (immediateDraft(task.payloadJson())) {
                    // An immediate draft never belongs to the task inbox; clear
                    // the compensation row instead of projecting a fake notice.
                    try { notificationRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
                    continue;
                }
                if (!phases.allTerminal(task.taskId())) continue;
                TaskFact current = tasks.find(task.taskId()).orElse(task);
                String source = field(current.payloadJson(), "source", "office");
                recordTerminalNotification(current, source,
                        terminalNotice(current.status(), current, null).payload());
            } catch (RuntimeException ignored) {
                try { notificationRetries.failed(task.taskId(), "notification retry failed"); } catch (RuntimeException ignoredAgain) { }
            }
        }
    }

    private String produceTaskReport(TaskFact task, String outcome) {
        // Runtime reconcileTask never builds a task report for immediate drafts;
        // their result lives on the action record itself.
        if (immediateDraft(task.payloadJson())) {
            cleanupExecutionIdentity(task.taskId());
            return null;
        }
        if (reports == null || reportComposer == null) {
            cleanupExecutionIdentity(task.taskId());
            return null;
        }
        List<TaskPhaseStore.PhaseResult> results;
        try { results = phases.terminalPhases(task.taskId()); }
        catch (RuntimeException unavailable) { return reportFailure(task, "任务阶段结果暂不可读"); }
        String payload;
        try { payload = reportComposer.compose(task, results, outcome, clock.instant()); }
        catch (RuntimeException invalid) {
            return reportFailure(task, invalid.getMessage() == null || invalid.getMessage().isBlank()
                    ? "任务检查点证据无效" : invalid.getMessage());
        }
        if (payload == null) {
            // No server-evidenced report was requested: the durable phase rows
            // are the execution report, and no protected identity is needed.
            cleanupExecutionIdentity(task.taskId());
            return null;
        }
        String phaseId = results.isEmpty() ? task.taskId() : results.get(results.size() - 1).phaseId();
        Instant checkpointAt = results.isEmpty() ? clock.instant() : results.get(results.size() - 1).dueAt();
        String tenant = field(task.payloadJson(), "tenantCode", "");
        String owner = field(task.payloadJson(), "ownerKey", "");
        String source = field(task.payloadJson(), "source", "office");
        String objective = field(task.payloadJson(), "objective", field(task.payloadJson(), "title", ""));
        if (executionIdentities == null) return reportFailure(task, "任务报表执行身份存储未配置");
        RequestIdentity identity;
        try {
            var stored = executionIdentities.loadForTask(task.taskId()).orElse(null);
            if (stored == null) return reportFailure(task, "任务报表执行身份缺失");
            if (!tenant.equals(stored.tenantCode()) || !owner.equals(stored.ownerKey())) {
                return reportFailure(task, "任务报表执行身份与任务归属不一致");
            }
            identity = new RequestIdentity(stored.authorization(), stored.tenantCode(),
                    IotSourceEnum.parse(source), stored.ownerKey());
        } catch (RuntimeException unavailable) {
            return reportFailure(task, "任务报表执行身份不可用");
        }
        try {
            java.util.Optional<com.ehe.iotagent.report.ReportFact> report = reports.produce(
                    new ReportFactProducer.ReportCheckpoint(task.taskId(), phaseId, source, tenant, owner,
                            objective, outcome, checkpointAt, clock.instant(), payload), identity);
            if (report == null) return reportFailure(task, "报表生成不可用");
            if (report.isEmpty()) {
                // The report module declined deterministically (source, request
                // or terminal filter): the execution record stays authoritative.
                if (reportRetries != null) {
                    try { reportRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
                }
                cleanupExecutionIdentity(task.taskId());
                return null;
            }
            auditReportProduced(task, phaseId, report.get().reportId());
            if (reportRetries != null) {
                try { reportRetries.succeeded(task.taskId()); } catch (RuntimeException ignored) { }
            }
            // Runtime deletes the task credential once the terminal result is
            // reconciled; the encrypted identity has no further execution use.
            cleanupExecutionIdentity(task.taskId());
            return null;
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? "报表生成失败" : failure.getMessage();
            return reportFailure(task, message);
        }
    }

    /** Protected-identity cleanup entry: durable stores remove the encrypted credential. */
    private void cleanupExecutionIdentity(String taskId) {
        if (executionIdentities == null || taskId == null || taskId.isBlank()) return;
        try {
            executionIdentities.deleteForTask(taskId);
        } catch (RuntimeException ignored) {
            // A failed cleanup leaves an unused encrypted row; it never blocks
            // the terminal fact and is retried on the next terminal pass.
        }
    }

    private String reportFailure(TaskFact task, String message) {
        if (reportRetries != null) {
            try { reportRetries.failed(task.taskId(), message); }
            catch (RuntimeException persistenceFailure) {
                return message + "；报表失败状态未能持久化: " + persistenceFailure.getMessage();
            }
        }
        return message;
    }

    private static String quote(String value) {
        return MAPPER.getNodeFactory().textNode(value == null ? "" : value).toString();
    }

    /**
     * Runtime recoverInterrupted: plan-sourced pending phases whose moment
     * passed while the service was down are missed without dispatch, executing
     * phases are closed as interrupted, and every affected task is reconciled
     * with its report, notification and identity cleanup.
     */
    public int recover() {
        Set<String> touchedTasks = new LinkedHashSet<>();
        List<TaskPhaseStore.TaskPhase> missed = phases.missElapsed(clock.instant());
        for (TaskPhaseStore.TaskPhase phase : missed) {
            touchedTasks.add(phase.taskId());
            auditCheckpointOutcome(phase, tasks.find(phase.taskId()).orElse(null),
                    "MISSED", "服务重启时检查点已过，未下发", false, null);
        }
        List<TaskPhaseStore.TaskPhase> recovered = phases.recoverInterrupted(clock.instant());
        for (TaskPhaseStore.TaskPhase phase : recovered) {
            touchedTasks.add(phase.taskId());
            auditCheckpointOutcome(phase, tasks.find(phase.taskId()).orElse(null),
                    "INTERRUPTED", "service_restart", null, null);
        }
        for (String taskId : touchedTasks) {
            if (!phases.allTerminal(taskId)) continue;
            TaskFact task = tasks.find(taskId).orElse(null);
            if (task == null || isTerminal(task.status())) continue;
            boolean wasMissed = missed.stream().anyMatch(phase -> phase.taskId().equals(taskId));
            finalizeIfIntervalEnded(null, task, wasMissed ? "MISSED" : "INTERRUPTED",
                    wasMissed ? "{\"outcome\":\"missed\",\"reason\":\"服务重启时检查点已过，未下发\"}"
                            : "{\"outcome\":\"unknown\",\"reason\":\"service_restart\"}", null);
        }
        reconcileFinishedTasks(new LinkedHashSet<>(), Set.of());
        retryNotifications();
        return recovered.size() + missed.size();
    }
    public ScanResult scan() { return scanOnce(); }

    public int recoverInterrupted() { return recover(); }
    /**
     * Runtime stopAndDrain semantics: request stop, then actually wait for the
     * in-flight scan (its claimed phases run to their terminal facts) so a
     * restart never abandons dispatched work to background threads.
     */
    public void stopAndDrain() {
        stopping = true;
        interruptGateways();
        boolean interrupted = false;
        synchronized (this) {
            while (scanning.get()) {
                try {
                    wait(1000);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private void terminalReport(TaskFact task, TaskPhaseStore.TaskPhase phase,
                                TaskExecutionGateway.TaskExecutionResult result) {
        if (!isTerminal(result.outcome())) return;
        try {
            String tenant = field(task.payloadJson(), "tenantCode", "");
            String owner = field(task.payloadJson(), "ownerKey", "");
            if (tenant.isBlank() || owner.isBlank() || immediateDraft(task.payloadJson())) return;
            reports.produce(new ReportFactProducer.ReportCheckpoint(
                    phase.taskId(), phase.phaseId(), field(task.payloadJson(), "source", phase.source()),
                    tenant, owner,
                    taskObjective(task.payloadJson()),
                    result.outcome(), phase.dueAt(), clock.instant(), result.payloadJson()))
                    .ifPresent(fact -> auditReportProduced(task, phase.phaseId(), fact.reportId()));
        } catch (RuntimeException ignored) {
            // Report persistence is best effort after terminal facts are durable;
            // never redispatch a completed checkpoint because artifact storage is unavailable.
        }
    }

    private TaskFact completeTask(String taskId, String fallbackOutcome, String forcedOutcome) {
        for (int attempt = 0; attempt < 8; attempt++) {
            if (!phases.allTerminal(taskId)) return null;
            TaskFact current = tasks.find(taskId).orElse(null);
            if (current == null || isTerminal(current.status())) return current;
            String status = forcedOutcome != null ? forcedOutcome
                    : aggregateOutcome(phases.outcomes(taskId), fallbackOutcome);
            Optional<TaskFact> update = tasks.updateIfVersion(new TaskFact(
                    current.taskId(), current.version() + 1, status, current.payloadJson(), current.idempotencyKey()), current.version());
            if (update.isPresent()) return update.get();
        }
        return tasks.find(taskId).filter(task -> isTerminal(task.status())).orElse(null);
    }

    private static boolean dispatchable(String status) {
        // Interval tasks run as EXECUTING once their window started.
        return "SCHEDULED".equals(status) || "EXECUTING".equals(status);
    }

    /**
     * Runtime aggregateStatus: interrupted wins over failure, all-missed stays
     * missed, satisfied counts as completed, and cancelled phases keep earlier
     * completions on partial instead of collapsing to failed.
     */
    private static String aggregateOutcome(List<String> values, String fallback) {
        if (values == null || values.isEmpty()) return normalizeOutcome(fallback);
        boolean interrupted = false;
        boolean allMissed = true;
        boolean allCompleted = true;
        boolean allCompletedOrUnconfirmed = true;
        boolean someGood = false;
        for (String value : values) {
            String outcome = normalizeOutcome(value);
            interrupted |= "INTERRUPTED".equals(outcome);
            allMissed &= "MISSED".equals(outcome);
            boolean completed = "COMPLETED".equals(outcome) || "SATISFIED".equals(outcome);
            boolean unconfirmed = "UNCONFIRMED".equals(outcome);
            allCompleted &= completed;
            allCompletedOrUnconfirmed &= completed || unconfirmed;
            someGood |= completed || "PARTIAL".equals(outcome) || unconfirmed;
        }
        if (interrupted) return "INTERRUPTED";
        if (allMissed) return "MISSED";
        if (allCompleted) return "COMPLETED";
        if (allCompletedOrUnconfirmed) return "UNCONFIRMED";
        return someGood ? "PARTIAL" : "FAILED";
    }

    private static String normalizeOutcome(String value) {
        return value == null ? "FAILED" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String invalidationKind(RuntimeException error) {
        return error instanceof TaskExecutionGateway.DispatchException dispatch ? dispatch.reasonCode() : null;
    }

    private static String notificationId(String taskId, String recipient) {
        return UUID.nameUUIDFromBytes((taskId + "\u0000" + recipient).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean isTerminal(String value) {
        return "COMPLETED".equals(value) || "PARTIAL".equals(value) || "FAILED".equals(value)
                || "UNCONFIRMED".equals(value) || "INTERRUPTED".equals(value) || "CANCELLED".equals(value)
                || "MISSED".equals(value) || "SATISFIED".equals(value);
    }
    private static String taskObjective(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String direct = root.path("objective").asText("");
            if (!direct.isBlank()) return direct;
            JsonNode phases = root.path("phases");
            if (phases.isArray()) {
                for (JsonNode phase : phases) {
                    String phaseObjective = phase.path("read").path("agent").path("objective").asText("");
                    if (!phaseObjective.isBlank()) return phaseObjective;
                    phaseObjective = phase.path("objective").asText("");
                    if (!phaseObjective.isBlank()) return phaseObjective;
                }
            }
        } catch (Exception ignored) {
            // Malformed task metadata is already handled fail-closed by the
            // authorization fields; it must not prevent terminal persistence.
        }
        return "";
    }

    /** Runtime source_type filter: immediate drafts are action history, not task reports or notifications. */
    private static boolean immediateDraft(String payloadJson) {
        JsonNode payload = read(payloadJson);
        if ("immediate_draft".equals(payload.path("sourceType").asText(""))) return true;
        return "immediate_draft".equals(payload.path("source").path("type").asText(""));
    }

    private static String field(String json, String key, String fallback) {
        try {
            JsonNode value = MAPPER.readTree(json).path(key);
            return value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception invalid) {
            return MAPPER.createObjectNode();
        }
    }

    /** Minimal JSON object builder that splices pre-serialized fragments without re-parsing. */
    private static final class ObjectNodeBuilder {
        private final StringBuilder body = new StringBuilder();
        private boolean empty = true;
        void put(String key, String value) {
            raw(key, quote(value));
        }
        void raw(String key, String serializedJson) {
            if (!empty) body.append(',');
            body.append(quote(key)).append(':').append(serializedJson);
            empty = false;
        }
        @Override public String toString() {
            return "{" + body + "}";
        }
    }

    private record TerminalNotice(String payload, boolean reportRequested) { }

    /** One serial task chain's contribution to the scan result. */
    private record WorkerOutcome(String taskId, int claimed, int completed, int interrupted) { }

    public record ScanResult(int attempted, int claimed, int completed, int interrupted) { }
}
