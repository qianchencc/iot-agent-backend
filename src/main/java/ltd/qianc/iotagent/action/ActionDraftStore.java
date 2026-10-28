package ltd.qianc.iotagent.action;

import java.util.List;
import java.util.Optional;

/** Long-lived business-fact boundary; implementations may be MySQL-backed. */
public interface ActionDraftStore {

    ActionDraftFact create(ActionDraftFact draft);

    Optional<ActionDraftFact> find(String draftId);

    ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement);

    /** Persistent lookup required for idempotent retries; a missing implementation is unsafe. */
    default Optional<ActionDraftFact> findByIdempotencyKey(String idempotencyKey) {
        throw new ActionDraftUnavailableException("草案幂等存储未配置");
    }

    /** Persistent frontier lookup required to enforce one pending draft per conversation. */
    default List<ActionDraftFact> findByConversation(String conversationId) {
        throw new ActionDraftUnavailableException("草案会话存储未配置");
    }
}
