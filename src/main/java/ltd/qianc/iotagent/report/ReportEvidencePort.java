package ltd.qianc.iotagent.report;

import java.time.Instant;
import java.util.Map;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Prepares server-owned report evidence; does not save or publish a report. */
@FunctionalInterface
public interface ReportEvidencePort {
    Map<String, Object> prepare(RequestIdentity identity, Map<String, Object> request, Instant checkpointAt);
}
