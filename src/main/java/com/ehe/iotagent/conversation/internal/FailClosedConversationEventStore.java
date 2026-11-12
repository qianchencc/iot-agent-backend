package com.ehe.iotagent.conversation.internal;

import java.util.List;
import java.util.Optional;
import com.ehe.iotagent.conversation.ConversationEventFact;
import com.ehe.iotagent.conversation.ConversationEventStore;
import com.ehe.iotagent.error.BusinessException;

/** Default production wiring fails closed; no volatile event history is permitted. */
public final class FailClosedConversationEventStore implements ConversationEventStore {

    private static BusinessException unavailable() {
        return new BusinessException("CONVERSATION_STORE_UNAVAILABLE", "会话事件存储暂不可用，请稍后重试", 503);
    }

    @Override
    public ConversationEventFact append(ConversationEventFact event) {
        throw unavailable();
    }

    @Override
    public List<ConversationEventFact> read(String conversationId) {
        throw unavailable();
    }

    @Override
    public Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey) {
        throw unavailable();
    }
}
