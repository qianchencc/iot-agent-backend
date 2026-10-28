package ltd.qianc.iotagent.action.internal;

import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.action.ActionDraftFact;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.action.ActionDraftUnavailableException;

/** Business facts never silently fall back to process memory. */
public final class FailClosedActionDraftStore implements ActionDraftStore {
    private final String reason;

    public FailClosedActionDraftStore(String reason) {
        this.reason = reason;
    }

    @Override public ActionDraftFact create(ActionDraftFact draft) { throw unavailable(); }
    @Override public Optional<ActionDraftFact> find(String draftId) { throw unavailable(); }
    @Override public ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement) { throw unavailable(); }
    @Override public Optional<ActionDraftFact> findByIdempotencyKey(String idempotencyKey) { throw unavailable(); }
    @Override public List<ActionDraftFact> findByConversation(String conversationId) { throw unavailable(); }

    private ActionDraftUnavailableException unavailable() {
        return new ActionDraftUnavailableException(reason);
    }
}
