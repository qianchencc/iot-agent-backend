package ltd.qianc.iotagent.report;

import java.time.Instant;
import java.util.Optional;

/**
 * Deep seam for turning a terminal task checkpoint payload into a durable report
 * fact. The task scanner owns lifecycle timing; report production owns payload
 * normalization and immutable persistence.
 */
public interface ReportFactProducer {

    Optional<ReportFact> produce(ReportCheckpoint checkpoint);

    /** A terminal checkpoint projection that does not expose task internals. */
    record ReportCheckpoint(
            String taskId,
            String phaseId,
            String source,
            String tenantCode,
            String ownerKey,
            String objective,
            String outcome,
            Instant checkpointAt,
            Instant generatedAt,
            String payloadJson) {
        public ReportCheckpoint {
            require(taskId, "taskId");
            require(phaseId, "phaseId");
            require(source, "source");
            require(tenantCode, "tenantCode");
            require(ownerKey, "ownerKey");
            if (objective == null) objective = "";
            require(outcome, "outcome");
            require(payloadJson, "payloadJson");
            if (checkpointAt == null) throw new IllegalArgumentException("checkpointAt is required");
            if (generatedAt == null) throw new IllegalArgumentException("generatedAt is required");
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }
    }
}
