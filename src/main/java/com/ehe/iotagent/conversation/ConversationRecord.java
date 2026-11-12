package com.ehe.iotagent.conversation;

import java.time.Instant;

/** Durable conversation identity and retention state. */
public record ConversationRecord(
        String id,
        String tenantCode,
        String ownerKey,
        String source,
        Instant createdAt,
        Instant lastActiveAt,
        boolean cleared) {
    public ConversationRecord {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (tenantCode == null || tenantCode.isBlank()) throw new IllegalArgumentException("tenantCode is required");
        if (ownerKey == null || ownerKey.isBlank()) throw new IllegalArgumentException("ownerKey is required");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source is required");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        if (lastActiveAt == null) throw new IllegalArgumentException("lastActiveAt is required");
    }
}
