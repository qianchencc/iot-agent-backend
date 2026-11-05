package ltd.qianc.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.storage.internal.JdbcTaskFactStore;
import ltd.qianc.iotagent.storage.internal.JdbcTaskPlanStore;
import ltd.qianc.iotagent.storage.internal.JdbcTaskProposalStore;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProposalStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Public storage construction seam for durable task stores and authorized read projections. */
public final class TaskFactStoresFactory {
    private TaskFactStoresFactory() { }

    public static TaskFactStore taskFacts(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcTaskFactStore(jdbc, transactions, mapper);
    }

    public static TaskProposalStore taskProposals(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcTaskProposalStore(jdbc, transactions, mapper);
    }

    public static TaskPlanStore taskPlans(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcTaskPlanStore(jdbc, transactions, mapper);
    }

    public static ltd.qianc.iotagent.task.TaskPhaseStore taskPhases(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new ltd.qianc.iotagent.storage.internal.JdbcTaskPhaseStore(jdbc, transactions, mapper);
    }

    public static ltd.qianc.iotagent.action.ImmediateActionHistory immediateActionHistory(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new ltd.qianc.iotagent.storage.internal.JdbcImmediateActionHistory(jdbc, transactions, mapper);
    }

    public static ltd.qianc.iotagent.task.TaskProjectionStore taskProjections(
            JdbcTemplate jdbc, ObjectMapper mapper, ltd.qianc.iotagent.task.TaskReadScope.Provider scopes) {
        return new ltd.qianc.iotagent.storage.internal.JdbcTaskProjectionStore(jdbc, mapper, scopes);
    }
}
