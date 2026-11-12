package com.ehe.iotagent.task;

import java.time.Instant;
import java.util.List;

/** Public seam for trusted, server-authored report checkpoint composition. */
public interface TaskReportCheckpointComposer {
    String compose(TaskFact task, List<TaskPhaseStore.PhaseResult> results,
                   String outcome, Instant generatedAt);

    /**
     * Runtime summarizeTaskReport projection shared by notifications and task
     * detail: checkpoint count, per-device execution tally (one device counted
     * once across checkpoints) and the honest energy impact. Returns null when
     * the task produced no inspection evidence.
     */
    default String summarize(TaskFact task, List<TaskPhaseStore.PhaseResult> results) {
        return null;
    }
}
