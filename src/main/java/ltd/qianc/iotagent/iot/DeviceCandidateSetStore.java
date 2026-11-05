package ltd.qianc.iotagent.iot;

import java.util.Optional;

/**
 * Durable candidate-set seam. Implementations must bind each set to the
 * discovering principal, source, conversation and catalog snapshot so a model
 * cannot reuse an invented or stale set.
 */
public interface DeviceCandidateSetStore {

    DeviceCandidateRecord save(DeviceCandidateRecord record);

    /** Resolve a full id or a conversation-local sN handle, never another conversation's set. */
    Optional<DeviceCandidateRecord> find(String candidateSetId, String conversationId);
}
