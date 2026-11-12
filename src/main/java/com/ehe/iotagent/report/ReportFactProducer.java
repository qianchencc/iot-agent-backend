package com.ehe.iotagent.report;

import java.time.Instant;
import java.util.Optional;
import com.ehe.iotagent.iot.RequestIdentity;

/**
 * Deep seam for turning a terminal task checkpoint payload into a durable report
 * fact. The task scanner owns lifecycle timing; report production owns the
 * declarative request, server evidence resolution and immutable persistence.
 *
 * <p>A checkpoint without a {@code reportRequest} object means the task produced
 * no report and returns {@link Optional#empty()}. A present but invalid,
 * unauthorized or unavailable request MUST fail loudly; it is never an empty
 * success.</p>
 */
public interface ReportFactProducer {

    /**
     * Compatibility entry point. It has no authenticated evidence identity, so a
     * present reportRequest fails with an explicit unavailable error rather than
     * trusting task-authored totals.
     */
    default Optional<ReportFact> produce(ReportCheckpoint checkpoint) {
        return produce(checkpoint, null);
    }

    Optional<ReportFact> produce(ReportCheckpoint checkpoint, RequestIdentity identity);

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
