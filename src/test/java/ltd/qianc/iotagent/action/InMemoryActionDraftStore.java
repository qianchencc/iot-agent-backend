package ltd.qianc.iotagent.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Public-seam fixture: same CAS and conversation lookup contract as the JDBC store. */
final class InMemoryActionDraftStore implements ActionDraftStore {
    private final Map<String, ActionDraftFact> drafts = new LinkedHashMap<>();
    private final int maxIdempotencyKeyLength;

    InMemoryActionDraftStore(ActionDraftFact... initial) {
        this(0, initial);
    }

    /** Non-zero max mirrors the durable VARCHAR limit so oversized keys fail like MySQL. */
    InMemoryActionDraftStore(int maxIdempotencyKeyLength, ActionDraftFact... initial) {
        this.maxIdempotencyKeyLength = maxIdempotencyKeyLength;
        for (ActionDraftFact draft : initial) drafts.put(draft.draftId(), draft);
    }

    @Override
    public ActionDraftFact create(ActionDraftFact draft) {
        if (maxIdempotencyKeyLength > 0 && draft.idempotencyKey().length() > maxIdempotencyKeyLength) {
            throw new IllegalStateException("Data too long for column 'idempotency_key'");
        }
        for (ActionDraftFact existing : drafts.values()) {
            if (existing.idempotencyKey().equals(draft.idempotencyKey())) return existing;
        }
        drafts.put(draft.draftId(), draft);
        return draft;
    }

    @Override
    public Optional<ActionDraftFact> find(String draftId) {
        return Optional.ofNullable(drafts.get(draftId));
    }

    @Override
    public ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement) {
        ActionDraftFact current = drafts.get(draftId);
        if (current == null || current.revision() != expectedRevision) {
            throw new IllegalStateException("action draft revision conflict");
        }
        drafts.put(draftId, replacement);
        return replacement;
    }

    @Override
    public Optional<ActionDraftFact> findByIdempotencyKey(String idempotencyKey) {
        return drafts.values().stream().filter(draft -> draft.idempotencyKey().equals(idempotencyKey)).findFirst();
    }

    @Override
    public List<ActionDraftFact> findByConversation(String conversationId) {
        List<ActionDraftFact> result = new ArrayList<>();
        for (ActionDraftFact draft : drafts.values()) {
            if (draft.payloadJson().contains("\"conversationId\":\"" + conversationId + "\"")) result.add(draft);
        }
        return result;
    }
}
