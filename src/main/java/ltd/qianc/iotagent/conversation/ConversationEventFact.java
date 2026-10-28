package ltd.qianc.iotagent.conversation;

public record ConversationEventFact(
        String conversationId,
        String turnId,
        long sequence,
        String type,
        String payloadJson,
        String idempotencyKey) {
}
