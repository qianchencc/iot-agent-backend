package com.ehe.iotagent.iot;

/** Server-owned conversation binding for discovered candidate sets. */
public record DeviceCandidateBinding(String conversationId) {
    public DeviceCandidateBinding {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        conversationId = conversationId.trim();
    }
}
