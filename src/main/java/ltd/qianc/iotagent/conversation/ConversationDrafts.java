package ltd.qianc.iotagent.conversation;

import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;

/** Owner-bound card facts for replay and supersession; never dispatches device commands. */
public interface ConversationDrafts {
    Map<String, Map<String, Object>> conversationDrafts(PrincipalInfo principal, String conversationId, String source);
    List<String> retireForConversation(PrincipalInfo principal, String conversationId, String source);
}
