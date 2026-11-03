package ltd.qianc.iotagent.conversation;

import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;

/** Current business state overlays immutable proposal events during conversation replay. */
@FunctionalInterface
public interface ConversationTaskCards {
    Map<String, Object> conversationProposalState(PrincipalInfo principal, String proposalId, String source);
}
