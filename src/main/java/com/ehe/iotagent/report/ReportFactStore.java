package com.ehe.iotagent.report;

import java.util.Optional;

/**
 * Durable local report facts. Payload JSON is an immutable snapshot: a repeated
 * report id MUST NOT replace the original artifact.
 */
public interface ReportFactStore {

    ReportFact save(ReportFact report);

    Optional<ReportFact> find(String reportId);
    /** Returns the newest immutable checkpoint report attached to a task, when present. */
    default Optional<ReportFact> findLatestByTaskId(String taskId) {
        return Optional.empty();
    }
}
