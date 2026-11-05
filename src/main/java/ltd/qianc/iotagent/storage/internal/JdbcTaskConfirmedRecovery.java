package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup/periodic recovery for confirmed-but-unmaterialized task proposals.
 * A confirmation must leave the proposal CONFIRMED, its fact and its scheduled
 * phases committed together; this sweep completes rows left behind by an
 * interrupted write so a user-confirmed task never silently disappears. It
 * only fills gaps by the same idempotency keys the facade uses
 * ({@code task:<proposalId>} / {@code plan:<proposalId>}) and never rewrites
 * existing facts, terminal phases or occurrence history.
 *
 * <p>The protected execution identity of a legacy interrupted confirm cannot
 * be rebuilt — credentials are never part of proposal payloads — so those rows
 * materialize without one and their execution fails closed instead of running
 * with a forged identity.</p>
 */
public final class JdbcTaskConfirmedRecovery implements ltd.qianc.iotagent.storage.TaskConfirmedRecovery {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TaskPlanStore plans;
    private final TaskFactStore tasks;
    private final TaskScheduler scheduler;
    private final ObjectMapper mapper;

    public JdbcTaskConfirmedRecovery(JdbcTemplate jdbc, TransactionTemplate transactions,
                                     TaskPlanStore plans, TaskFactStore tasks, TaskScheduler scheduler,
                                     ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override public RecoveryResult recoverOnce() {
        List<Candidate> candidates = jdbc.query(
                "SELECT q.proposal_id,q.payload_json FROM task_proposals q "
                        + "LEFT JOIN task_facts f ON f.idempotency_key=CONCAT('task:',q.proposal_id) "
                        + "LEFT JOIN task_plans p ON p.idempotency_key=CONCAT('plan:',q.proposal_id) "
                        + "WHERE q.kind='TASK' AND q.status='CONFIRMED' "
                        + "AND ((f.task_id IS NULL AND p.plan_id IS NULL) OR f.status='SCHEDULED') "
                        + "ORDER BY q.id",
                (rs, row) -> new Candidate(rs.getString(1), rs.getString(2)));
        int recovered = 0;
        int skipped = 0;
        for (Candidate candidate : candidates) {
            OutcomeEnum outcome = recoverOne(candidate);
            if (outcome == OutcomeEnum.CHANGED) recovered++;
            else if (outcome == OutcomeEnum.UNRECOVERABLE) skipped++;
        }
        return new RecoveryResult(recovered, skipped);
    }

    private OutcomeEnum recoverOne(Candidate candidate) {
        try {
            OutcomeEnum outcome = transactions.execute(status -> {
                String state = jdbc.query("SELECT status FROM task_proposals WHERE proposal_id=? FOR UPDATE",
                        (rs, row) -> rs.getString(1), candidate.proposalId()).stream().findFirst().orElse(null);
                if (!"CONFIRMED".equals(state)) return OutcomeEnum.INTACT;
                JsonNode payload;
                try {
                    payload = mapper.readTree(candidate.payloadJson());
                } catch (Exception malformed) {
                    return OutcomeEnum.UNRECOVERABLE;
                }
                if (!payload.isObject()) return OutcomeEnum.UNRECOVERABLE;
                return payload.path("repeat").isObject()
                        ? recoverPlan(candidate.proposalId(), payload)
                        : recoverTask(candidate.proposalId(), payload);
            });
            return outcome == null ? OutcomeEnum.INTACT : outcome;
        } catch (RuntimeException interrupted) {
            // One poison row must not block recovery of the rest; the next sweep retries it.
            return OutcomeEnum.UNRECOVERABLE;
        }
    }

    private OutcomeEnum recoverPlan(String proposalId, JsonNode payload) {
        String key = "plan:" + proposalId;
        if (plans.findByIdempotencyKey(key).isPresent()) return OutcomeEnum.INTACT;
        plans.save(new TaskPlanFact(proposalId, 1, "ENABLED", payload.toString(), key));
        return OutcomeEnum.CHANGED;
    }

    private OutcomeEnum recoverTask(String proposalId, JsonNode payload) {
        JsonNode phases = payload.path("phases");
        if (!phases.isArray() || phases.isEmpty()) return OutcomeEnum.UNRECOVERABLE;
        String key = "task:" + proposalId;
        TaskFact task = tasks.findByIdempotencyKey(key).orElse(null);
        boolean changed = false;
        if (task == null) {
            task = tasks.save(new TaskFact(proposalId, 1, "SCHEDULED", withPhaseIds(payload), key));
            changed = true;
        }
        if (!"SCHEDULED".equals(task.status())) return changed ? OutcomeEnum.CHANGED : OutcomeEnum.INTACT;
        JsonNode stored;
        try {
            stored = mapper.readTree(task.payloadJson());
        } catch (Exception malformed) {
            return changed ? OutcomeEnum.CHANGED : OutcomeEnum.UNRECOVERABLE;
        }
        JsonNode storedPhases = stored.path("phases");
        if (!storedPhases.isArray()) return changed ? OutcomeEnum.CHANGED : OutcomeEnum.UNRECOVERABLE;
        for (JsonNode phase : storedPhases) {
            String phaseId = phase.path("phaseId").asText("");
            String at = phase.path("at").asText("");
            if (phaseId.isBlank() || at.isBlank()) continue;
            if (!"PENDING".equalsIgnoreCase(phase.path("status").asText("PENDING"))) continue;
            Instant dueAt;
            try {
                dueAt = Instant.parse(at);
            } catch (RuntimeException invalid) {
                continue;
            }
            boolean missing = jdbc.query("SELECT 1 FROM task_phases WHERE phase_id=?",
                    (rs, row) -> 1, phaseId).isEmpty();
            if (!missing) continue;
            scheduler.schedule(task.taskId(), phaseId, dueAt);
            changed = true;
        }
        return changed ? OutcomeEnum.CHANGED : OutcomeEnum.INTACT;
    }

    /** Uses the same identity rule as normal confirmation. */
    private String withPhaseIds(JsonNode payload) {
        ObjectNode root = (ObjectNode) payload.deepCopy();
        ltd.qianc.iotagent.task.TaskPhaseIds.assignMissing(root);
        return root.toString();
    }

    private enum OutcomeEnum { CHANGED, INTACT, UNRECOVERABLE }
    private record Candidate(String proposalId, String payloadJson) { }
}
