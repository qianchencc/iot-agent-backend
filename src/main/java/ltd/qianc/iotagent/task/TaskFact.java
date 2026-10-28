package ltd.qianc.iotagent.task;

public record TaskFact(
        String taskId,
        long version,
        String status,
        String payloadJson,
        String idempotencyKey) {

    public TaskFact {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalArgumentException("payloadJson is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
    }

    public TaskFact(String taskId, long version, String status, String payloadJson) {
        this(taskId, version, status, payloadJson, taskId);
    }
}
