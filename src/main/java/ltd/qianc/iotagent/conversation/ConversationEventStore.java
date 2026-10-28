package ltd.qianc.iotagent.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationEventStore {

    ConversationEventFact append(ConversationEventFact event);

    List<ConversationEventFact> read(String conversationId);

    Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey);
}
