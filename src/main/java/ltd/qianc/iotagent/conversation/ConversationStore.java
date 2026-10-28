package ltd.qianc.iotagent.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable conversation and turn lifecycle facts; production implementations persist them. */
public interface ConversationStore {
    ConversationRecord create(String tenantCode, String ownerKey, String source, Instant createdAt);
    Optional<ConversationRecord> find(String conversationId, String tenantCode, String ownerKey);
    Optional<ConversationRecord> current(String tenantCode, String ownerKey, String source, Instant activeAfter);
    ConversationTurn startTurn(String conversationId, String content, Instant startedAt, String idempotencyKey);
    Optional<ConversationTurn> findTurnByIdempotencyKey(String conversationId, String idempotencyKey);
    Optional<ConversationTurn> latestTurn(String conversationId);
    List<ConversationTurn> turns(String conversationId);
    void clear(String conversationId);
    void discardTurn(String conversationId, String turnId);
}
