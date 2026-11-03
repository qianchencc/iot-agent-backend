package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.List;

/** Public seam for trusted, server-authored report checkpoint composition. */
public interface TaskReportCheckpointComposer {
    String compose(TaskFact task, List<TaskPhaseStore.PhaseResult> results,
                   String outcome, Instant generatedAt);
}
