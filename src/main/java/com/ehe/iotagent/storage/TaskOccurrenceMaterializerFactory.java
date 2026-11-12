package com.ehe.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.ehe.iotagent.storage.internal.JdbcTaskRecurrenceMaterializer;
import com.ehe.iotagent.task.TaskExecutionIdentityStore;
import com.ehe.iotagent.task.TaskOccurrenceMaterializer;

/** Public storage construction seam for the durable recurring-plan projection. */
public final class TaskOccurrenceMaterializerFactory {
    private TaskOccurrenceMaterializerFactory() { }

    public static TaskOccurrenceMaterializer jdbc(JdbcTemplate jdbc, TransactionTemplate transactions,
                                                   ObjectMapper mapper, Clock clock, int batchSize,
                                                   TaskExecutionIdentityStore identities) {
        return new JdbcTaskRecurrenceMaterializer(jdbc, transactions, mapper, clock, batchSize, identities);
    }
}
