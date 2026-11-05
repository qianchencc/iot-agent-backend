package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.task.internal.JdbcTaskExecutionIdentityStore;
import org.springframework.jdbc.core.JdbcTemplate;

/** Public construction seam for encrypted, durable task execution identities. */
public final class TaskExecutionIdentityStoreFactory {
    private TaskExecutionIdentityStoreFactory() { }

    public static TaskExecutionIdentityStore jdbc(JdbcTemplate jdbc, ObjectMapper mapper, String credentialKey) {
        return new JdbcTaskExecutionIdentityStore(jdbc, mapper, credentialKey);
    }
}
