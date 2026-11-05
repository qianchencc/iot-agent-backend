package ltd.qianc.iotagent.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;

final class InMemoryCandidateStore implements DeviceCandidateSetStore {
    private final Map<String, DeviceCandidateRecord> records = new LinkedHashMap<>();

    InMemoryCandidateStore(DeviceCandidateRecord... values) {
        for (DeviceCandidateRecord value : values) records.put(value.candidateSetId(), value);
    }

    @Override
    public DeviceCandidateRecord save(DeviceCandidateRecord record) {
        records.put(record.candidateSetId(), record);
        return record;
    }

    @Override
    public Optional<DeviceCandidateRecord> find(String candidateSetId, String conversationId) {
        DeviceCandidateRecord direct = records.get(candidateSetId);
        if (direct != null && conversationId.equals(direct.conversationId())) return Optional.of(direct);
        return records.values().stream()
                .filter(record -> conversationId.equals(record.conversationId()) && candidateSetId.equals(record.handle()))
                .findFirst();
    }
}
