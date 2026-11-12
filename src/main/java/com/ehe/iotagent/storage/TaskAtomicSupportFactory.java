package com.ehe.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import com.ehe.iotagent.storage.internal.JdbcTaskAtomicTransactions;
import com.ehe.iotagent.storage.internal.JdbcTaskConfirmedRecovery;
import com.ehe.iotagent.task.TaskAtomicTransactions;
import com.ehe.iotagent.task.TaskFactStore;
import com.ehe.iotagent.task.TaskPlanStore;
import com.ehe.iotagent.task.TaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Public storage construction seam for the task transaction slice. */
public final class TaskAtomicSupportFactory {
    private TaskAtomicSupportFactory() { }

    public static TaskAtomicTransactions atomicTransactions(TransactionTemplate transactions) {
        return new JdbcTaskAtomicTransactions(transactions);
    }

    public static TaskConfirmedRecovery confirmedRecovery(JdbcTemplate jdbc, TransactionTemplate transactions,
                                                          TaskPlanStore plans, TaskFactStore tasks,
                                                          TaskScheduler scheduler, ObjectMapper mapper) {
        return new JdbcTaskConfirmedRecovery(jdbc, transactions, Objects.requireNonNull(plans, "plans"),
                Objects.requireNonNull(tasks, "tasks"), Objects.requireNonNull(scheduler, "scheduler"), mapper);
    }
}
