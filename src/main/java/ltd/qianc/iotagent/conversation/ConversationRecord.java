package ltd.qianc.iotagent.conversation;

import java.time.Instant;

public record ConversationRecord(
        String id,
        String tenantCode,
        String ownerKey,
        String source,
        Instant createdAt,
        boolean cleared) {
}
