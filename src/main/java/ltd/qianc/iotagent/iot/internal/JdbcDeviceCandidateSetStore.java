package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable candidate-set facts; a missing row is a stale/missing reference, never an empty successful set. */
public final class JdbcDeviceCandidateSetStore implements DeviceCandidateSetStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDeviceCandidateSetStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public DeviceCandidateRecord save(DeviceCandidateRecord record) {
        try {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO device_candidate_sets(candidate_set_id,conversation_id,tenant_code,owner_key,source,"
                            + "catalog_fingerprint,payload_json,complete,total_matches,created_at,create_time,update_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    record.candidateSetId(), record.conversationId(), record.tenantCode(), record.ownerKey(),
                    record.source().wireValue(), record.catalogFingerprint(), mapper.writeValueAsString(record),
                    record.complete() ? 1 : 0, record.totalMatches(), record.createdAt(), now, now);
            return record;
        } catch (DataAccessException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实暂不可保存", 503);
        }
    }

    @Override
    public Optional<DeviceCandidateRecord> find(String candidateSetId) {
        try {
            return jdbc.query("SELECT payload_json FROM device_candidate_sets WHERE candidate_set_id=?",
                    (rs, row) -> {
                        try {
                            return mapper.readValue(rs.getString(1), DeviceCandidateRecord.class);
                        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                            throw new IllegalStateException("candidate set payload is malformed", malformed);
                        }
                    },
                    candidateSetId).stream().findFirst();
        } catch (DataAccessException | IllegalStateException error) {
            throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实暂不可读取", 503);
        }
    }
}
