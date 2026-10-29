package ltd.qianc.iotagent.report;

import java.util.Optional;

/**
 * Durable local report facts. Payload JSON is an immutable snapshot: a repeated
 * report id MUST NOT replace the original artifact.
 */
public interface ReportFactStore {

    ReportFact save(ReportFact report);

    Optional<ReportFact> find(String reportId);
}
