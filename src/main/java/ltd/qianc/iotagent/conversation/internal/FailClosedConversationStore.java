package ltd.qianc.iotagent.conversation.internal;

import java.time.Instant;
import java.util.Optional;
import ltd.qianc.iotagent.conversation.ConversationRecord;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.conversation.ConversationTurn;
import ltd.qianc.iotagent.error.BusinessException;

/**
 * Default wiring deliberately fails closed until a durable adapter is configured.
 * It is not an in-memory production fallback.
 */
public final class FailClosedConversationStore implements ConversationStore {

    private static BusinessException unavailable() {
        return new BusinessException("CONVERSATION_STORE_UNAVAILABLE", "会话存储暂不可用，请稍后重试", 503);
    }

    @Override
    public ConversationRecord create(String tenantCode, String ownerKey, String source, Instant createdAt) {
        throw unavailable();
    }

    @Override
    public Optional<ConversationRecord> find(String conversationId, String tenantCode, String ownerKey) {
        throw unavailable();
    }

    @Override
    public Optional<ConversationRecord> current(String tenantCode, String ownerKey, String source, Instant activeAfter) {
        throw unavailable();
    }

    @Override
    public ConversationTurn startTurn(String conversationId, String content, Instant startedAt, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public Optional<ConversationTurn> findTurnByIdempotencyKey(String conversationId, String idempotencyKey) {
        throw unavailable();
    }

    @Override
    public Optional<ConversationTurn> latestTurn(String conversationId) {
        throw unavailable();
    }

    @Override
    public java.util.List<ConversationTurn> turns(String conversationId) {
        throw unavailable();
    }
    @Override
    public void clear(String conversationId) {
        throw unavailable();
    }

    @Override
    public void discardTurn(String conversationId, String turnId) {
        throw unavailable();
    }
}
