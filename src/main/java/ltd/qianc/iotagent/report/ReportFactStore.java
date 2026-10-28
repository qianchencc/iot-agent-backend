package ltd.qianc.iotagent.report;

import java.util.Optional;

public interface ReportFactStore {

    ReportFact save(ReportFact report);

    Optional<ReportFact> find(String reportId);
}
