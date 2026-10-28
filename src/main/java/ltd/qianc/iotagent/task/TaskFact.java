package ltd.qianc.iotagent.task;

public record TaskFact(
        String taskId,
        long version,
        String status,
        String payloadJson,
        String idempotencyKey) {
}
