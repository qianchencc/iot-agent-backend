package com.ehe.iotagent.admin;

import java.util.Map;

/** Read-only operational view of Java-owned MySQL Pi sessions. */
public interface PiSessionOperations {
    OperationResult health();

    record OperationResult(int status, String code, String message, Map<String, Object> details) {
        public static OperationResult unavailable() {
            return new OperationResult(503, "PI_SESSION_UNAVAILABLE", "MySQL 会话存储暂不可用", Map.of());
        }
    }
}
