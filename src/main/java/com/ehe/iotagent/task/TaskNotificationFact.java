package com.ehe.iotagent.task;

import java.time.Instant;

public record TaskNotificationFact(
        String notificationId,
        String taskId,
        String tenantCode,
        String recipientKey,
        String source,
        String outcome,
        Instant endedAt,
        Instant readAt,
        String payloadJson) {
    public TaskNotificationFact {
        require(notificationId, "notificationId");
        require(taskId, "taskId");
        require(tenantCode, "tenantCode");
        require(recipientKey, "recipientKey");
        require(source, "source");
        require(outcome, "outcome");
        if (endedAt == null) throw new IllegalArgumentException("endedAt is required");
        require(payloadJson, "payloadJson");
    }
    public TaskNotificationFact(String notificationId, String taskId, String recipientKey, String source,
                                String outcome, Instant endedAt, Instant readAt, String payloadJson) {
        this(notificationId, taskId, "unknown", recipientKey, source, outcome, endedAt, readAt, payloadJson);
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
