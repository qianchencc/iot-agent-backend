package com.ehe.iotagent.iot.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.iot.DeviceCandidateSetStore;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable candidate-set facts; a missing row is a stale/missing reference, never an empty successful set.
 *
 * <p>Handle allocation mirrors Runtime app.ts persistCandidateSet: sN is a per-conversation
 * counter persisted in device_candidate_set_sequences (one row per conversation). Allocation
 * and the fact insert share one transaction: the counter row lock serializes concurrent
 * writers (O(1), no coordinator), a failure rolls the whole transaction back and consumes
 * no number, and the (conversation_id, handle) unique key stays as the final backstop.
 */
public final class JdbcDeviceCandidateSetStore implements DeviceCandidateSetStore {
    private static final java.util.regex.Pattern SET_HANDLE = java.util.regex.Pattern.compile("^s\\d+$");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public JdbcDeviceCandidateSetStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource(), "jdbc dataSource")));
    }

    @Override
    public DeviceCandidateRecord save(DeviceCandidateRecord record) {
        try {
            DeviceCandidateRecord saved = transactions.execute(status -> {
                try {
                    DeviceCandidateRecord stored = record;
                    if (record.handle() == null || record.handle().isBlank()) {
                        stored = withHandle(record, "s" + allocateNextHandleNumber(record.conversationId()));
                    } else {
                        advanceSequenceForExplicitHandle(record.conversationId(), record.handle());
                    }
                    insert(stored);
                    return stored;
                } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                    throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实暂不可保存", 503);
                }
            });
            return Objects.requireNonNull(saved, "candidate set save transaction returned nothing");
        } catch (DataAccessException error) {
            throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实暂不可保存", 503);
        }
    }

    private DeviceCandidateRecord withHandle(DeviceCandidateRecord record, String handle) {
        return new DeviceCandidateRecord(record.candidateSetId(), record.conversationId(),
                record.tenantCode(), record.ownerKey(), record.source(), record.catalogFingerprint(),
                record.query(), record.members(), record.complete(), record.totalMatches(),
                record.createdAt(), record.resolutionMode(), handle);
    }

    /** UPSERT 确保计数行存在（自赋值无副作用，直接取行锁），随后同事务自增并回读。 */
    private void ensureSequenceRow(String conversationId) {
        jdbc.update("INSERT INTO device_candidate_set_sequences"
                        + "(conversation_id,last_handle_no,create_time,update_time) VALUES(?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE last_handle_no=last_handle_no",
                conversationId);
    }

    private long allocateNextHandleNumber(String conversationId) {
        ensureSequenceRow(conversationId);
        jdbc.update("UPDATE device_candidate_set_sequences SET last_handle_no=last_handle_no+1, "
                + "update_time=CURRENT_TIMESTAMP WHERE conversation_id=?", conversationId);
        Long number = jdbc.queryForObject(
                "SELECT last_handle_no FROM device_candidate_set_sequences WHERE conversation_id=?",
                Long.class, conversationId);
        return Objects.requireNonNull(number, "candidate set handle sequence row is missing");
    }

    /** 显式句柄同样推进序号到最大值，避免后续自动分配与之撞号；非 sN 句柄不占用序号域。 */
    private void advanceSequenceForExplicitHandle(String conversationId, String handle) {
        if (!SET_HANDLE.matcher(handle).matches()) {
            return;
        }
        long handleNumber;
        try {
            handleNumber = Long.parseLong(handle.substring(1));
        } catch (NumberFormatException outOfRange) {
            return;
        }
        ensureSequenceRow(conversationId);
        jdbc.update("UPDATE device_candidate_set_sequences SET last_handle_no=GREATEST(last_handle_no,?), "
                        + "update_time=CURRENT_TIMESTAMP WHERE conversation_id=?",
                handleNumber, conversationId);
    }

    private void insert(DeviceCandidateRecord record) throws com.fasterxml.jackson.core.JsonProcessingException {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO device_candidate_sets(candidate_set_id,conversation_id,tenant_code,owner_key,source,"
                        + "catalog_fingerprint,payload_json,complete,total_matches,created_at,handle,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                record.candidateSetId(), record.conversationId(), record.tenantCode(), record.ownerKey(),
                record.source().wireValue(), record.catalogFingerprint(), mapper.writeValueAsString(record),
                record.complete() ? 1 : 0, record.totalMatches(), record.createdAt(), record.handle(), now, now);
    }

    @Override
    public Optional<DeviceCandidateRecord> find(String candidateSetId, String conversationId) {
        try {
            Optional<DeviceCandidateRecord> byId = jdbc.query(
                    "SELECT payload_json FROM device_candidate_sets WHERE candidate_set_id=? AND conversation_id=?",
                    (rs, row) -> read(rs.getString(1)), candidateSetId, conversationId).stream().findFirst();
            if (byId.isPresent() || !SET_HANDLE.matcher(candidateSetId == null ? "" : candidateSetId).matches()) {
                return byId;
            }
            // sN is a per-conversation handle, not a global candidate-set identifier; index-backed.
            return jdbc.query("SELECT payload_json FROM device_candidate_sets "
                            + "WHERE conversation_id=? AND handle=? ORDER BY id DESC LIMIT 1",
                    (rs, row) -> read(rs.getString(1)),
                    conversationId, candidateSetId).stream().findFirst();
        } catch (DataAccessException | IllegalStateException error) {
            throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实暂不可读取", 503);
        }
    }

    private DeviceCandidateRecord read(String payload) throws IllegalStateException {
        try {
            return mapper.readValue(payload, DeviceCandidateRecord.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new IllegalStateException("candidate set payload is malformed", malformed);
        }
    }
}
