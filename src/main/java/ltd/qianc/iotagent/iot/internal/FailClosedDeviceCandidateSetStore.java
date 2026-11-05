package ltd.qianc.iotagent.iot.internal;

import java.util.Optional;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;

/** No local candidate membership is ever invented when durable storage is absent. */
public final class FailClosedDeviceCandidateSetStore implements DeviceCandidateSetStore {
    private final String reason;

    public FailClosedDeviceCandidateSetStore(String reason) {
        this.reason = reason;
    }

    @Override
    public DeviceCandidateRecord save(DeviceCandidateRecord record) {
        throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", reason, 503);
    }

    @Override
    public Optional<DeviceCandidateRecord> find(String candidateSetId, String conversationId) {
        throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", reason, 503);
    }
}
