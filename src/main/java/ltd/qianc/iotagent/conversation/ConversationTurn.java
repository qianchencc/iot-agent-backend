package ltd.qianc.iotagent.conversation;

import java.time.Instant;

public record ConversationTurn(
        String id,
        String conversationId,
        String content,
        Instant startedAt,
        String idempotencyKey) {
}
