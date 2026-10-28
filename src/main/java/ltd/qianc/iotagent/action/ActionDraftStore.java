package ltd.qianc.iotagent.action;

import java.util.Optional;

public interface ActionDraftStore {

    ActionDraftFact create(ActionDraftFact draft);

    Optional<ActionDraftFact> find(String draftId);

    ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement);
}
