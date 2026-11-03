package ltd.qianc.iotagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.storage.internal.JdbcTaskScheduler;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;

/** Public storage construction seam for the durable phase scheduler. */
public final class TaskSchedulerFactory {
    private TaskSchedulerFactory() { }

    public static TaskScheduler jdbc(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcTaskScheduler(jdbc, mapper);
    }
}
