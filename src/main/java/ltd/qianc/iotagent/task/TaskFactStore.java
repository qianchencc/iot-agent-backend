package ltd.qianc.iotagent.task;

import java.util.Optional;

public interface TaskFactStore {

    TaskFact save(TaskFact task);

    Optional<TaskFact> find(String taskId);

    Optional<TaskFact> findByIdempotencyKey(String idempotencyKey);
}
