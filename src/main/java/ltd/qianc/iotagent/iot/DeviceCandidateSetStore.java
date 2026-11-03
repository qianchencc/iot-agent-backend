package ltd.qianc.iotagent.iot;

import java.util.Optional;

/**
 * Durable candidate-set seam. Implementations must bind each set to the
 * discovering principal, source, conversation and catalog snapshot so a model
 * cannot reuse an invented or stale set.
 */
public interface DeviceCandidateSetStore {

    DeviceCandidateRecord save(DeviceCandidateRecord record);

    Optional<DeviceCandidateRecord> find(String candidateSetId);
}
