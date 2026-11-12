package com.ehe.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.storage.internal.JdbcTaskFactStore;
import com.ehe.iotagent.storage.internal.JdbcTaskPlanStore;
import com.ehe.iotagent.storage.internal.JdbcTaskProposalStore;
import com.ehe.iotagent.task.TaskFactStore;
import com.ehe.iotagent.task.TaskPlanStore;
import com.ehe.iotagent.task.TaskProposalStore;
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

    public static com.ehe.iotagent.task.TaskPhaseStore taskPhases(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new com.ehe.iotagent.storage.internal.JdbcTaskPhaseStore(jdbc, transactions, mapper);
    }

    public static com.ehe.iotagent.action.ImmediateActionHistory immediateActionHistory(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new com.ehe.iotagent.storage.internal.JdbcImmediateActionHistory(jdbc, transactions, mapper);
    }

    public static com.ehe.iotagent.task.TaskProjectionStore taskProjections(
            JdbcTemplate jdbc, ObjectMapper mapper, com.ehe.iotagent.task.TaskReadScope.Provider scopes) {
        return new com.ehe.iotagent.storage.internal.JdbcTaskProjectionStore(jdbc, mapper, scopes);
    }
}
