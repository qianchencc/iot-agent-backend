package com.ehe.iotagent.storage.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.ehe.iotagent.conversation.ConversationEventFact;
import com.ehe.iotagent.conversation.ConversationEventStore;
import com.ehe.iotagent.conversation.ConversationRecord;
import com.ehe.iotagent.conversation.ConversationStore;
import com.ehe.iotagent.conversation.ConversationTurn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JdbcConversationStore extends AbstractJdbcFactSupport implements ConversationStore, ConversationEventStore {
    public JdbcConversationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        super(jdbc, transactions, mapper);
    }

    @Override
    public ConversationRecord create(String tenantCode, String ownerKey, String source, Instant createdAt) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversations(conversation_id,tenant_code,owner_key,source,cleared,create_time,update_time) VALUES (?,?,?,?,0,?,?)",
                id, tenantCode, ownerKey, source, time(createdAt), time(createdAt));
        return new ConversationRecord(id, tenantCode, ownerKey, source, createdAt, createdAt, false);
    }

    @Override
    public Optional<ConversationRecord> find(String conversationId, String tenantCode, String ownerKey) {
        return one("SELECT conversation_id,tenant_code,owner_key,source,cleared,create_time,update_time FROM conversations WHERE conversation_id=? AND tenant_code=? AND owner_key=?",
                (rs, row) -> new ConversationRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getBoolean(5)),
                conversationId, tenantCode, ownerKey);
    }

    @Override
    public Optional<ConversationRecord> current(String tenantCode, String ownerKey, String source, Instant activeAfter) {
        return one("SELECT conversation_id,tenant_code,owner_key,source,cleared,create_time,update_time FROM conversations WHERE tenant_code=? AND owner_key=? AND source=? AND cleared=0 AND update_time>? ORDER BY update_time DESC LIMIT 1",
                (rs, row) -> new ConversationRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant(), rs.getBoolean(5)),
                tenantCode, ownerKey, source, time(activeAfter));
    }

    @Override
    public ConversationTurn startTurn(String conversationId, String content, Instant startedAt, String idempotencyKey) {
        String id = java.util.UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO conversation_turns(turn_id,conversation_id,content,idempotency_key,started_at,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                    id, conversationId, content, idempotencyKey, time(startedAt), time(startedAt), time(startedAt));
            jdbc.update("UPDATE conversations SET update_time=? WHERE conversation_id=?", time(startedAt), conversationId);
            return new ConversationTurn(id, conversationId, content, startedAt, idempotencyKey);
        } catch (DuplicateKeyException duplicate) {
            return findTurnByIdempotencyKey(conversationId, idempotencyKey).orElseThrow(() -> duplicate);
        }
    }

    @Override
    public Optional<ConversationTurn> findTurnByIdempotencyKey(String conversationId, String idempotencyKey) {
        return one("SELECT turn_id,conversation_id,content,started_at,idempotency_key FROM conversation_turns WHERE conversation_id=? AND idempotency_key=?",
                (rs, row) -> new ConversationTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                conversationId, idempotencyKey);
    }

    @Override
    public Optional<ConversationTurn> latestTurn(String conversationId) {
        return one("SELECT turn_id,conversation_id,content,started_at,idempotency_key FROM conversation_turns WHERE conversation_id=? ORDER BY create_time DESC,id DESC LIMIT 1",
                (rs, row) -> new ConversationTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)), conversationId);
    }

    @Override
    public List<ConversationTurn> turns(String conversationId) {
        return jdbc.query("SELECT turn_id,conversation_id,content,started_at,idempotency_key FROM conversation_turns WHERE conversation_id=? ORDER BY create_time,id",
                (rs, row) -> new ConversationTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)), conversationId);
    }

    @Override
    public void clear(String conversationId) {
        jdbc.update("UPDATE conversations SET cleared=1,update_time=? WHERE conversation_id=?", time(Instant.now()), conversationId);
    }

    @Override
    public void discardTurn(String conversationId, String turnId) {
        jdbc.update("DELETE FROM conversation_events WHERE conversation_id=? AND turn_id=?", conversationId, turnId);
        jdbc.update("DELETE FROM conversation_turns WHERE conversation_id=? AND turn_id=?", conversationId, turnId);
        jdbc.update("UPDATE conversations SET update_time=? WHERE conversation_id=?", time(Instant.now()), conversationId);
    }

    @Override
    public ConversationEventFact append(ConversationEventFact event) {
        try {
            jdbc.update("INSERT INTO conversation_events(conversation_id,turn_id,sequence_no,event_type,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)",
                    event.conversationId(), event.turnId(), event.sequence(), event.type(), event.payloadJson(), event.idempotencyKey(), time(Instant.now()), time(Instant.now()));
            return event;
        } catch (DuplicateKeyException duplicate) {
            return findByIdempotencyKey(event.conversationId(), event.idempotencyKey()).orElseThrow(() -> duplicate);
        }
    }

    @Override
    public List<ConversationEventFact> read(String conversationId) {
        return jdbc.query("SELECT conversation_id,turn_id,sequence_no,event_type,payload_json,idempotency_key FROM conversation_events WHERE conversation_id=? ORDER BY sequence_no,id",
                (rs, row) -> new ConversationEventFact(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)), conversationId);
    }

    @Override
    public Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey) {
        return one("SELECT conversation_id,turn_id,sequence_no,event_type,payload_json,idempotency_key FROM conversation_events WHERE conversation_id=? AND idempotency_key=?",
                (rs, row) -> new ConversationEventFact(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)), conversationId, idempotencyKey);
    }
}
