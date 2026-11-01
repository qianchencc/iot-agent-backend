package ltd.qianc.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ltd.qianc.iotagent.storage.internal.JdbcTaskRecurrenceMaterializer;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskOccurrenceMaterializer;

/** Public storage construction seam for the durable recurring-plan projection. */
public final class TaskOccurrenceMaterializerFactory {
    private TaskOccurrenceMaterializerFactory() { }

    public static TaskOccurrenceMaterializer jdbc(JdbcTemplate jdbc, TransactionTemplate transactions,
                                                   ObjectMapper mapper, Clock clock, int batchSize,
                                                   TaskExecutionIdentityStore identities) {
        return new JdbcTaskRecurrenceMaterializer(jdbc, transactions, mapper, clock, batchSize, identities);
    }
}
