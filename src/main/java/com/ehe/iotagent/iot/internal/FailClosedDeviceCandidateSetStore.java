package com.ehe.iotagent.iot.internal;

import java.util.Optional;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.iot.DeviceCandidateSetStore;

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
