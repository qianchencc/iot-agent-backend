package ltd.qianc.iotagent.task;

import java.time.LocalDate;

/** Durable identity and override state for one recurring-plan occurrence. */
public record TaskPlanOccurrence(
        String planId,
        LocalDate occurrenceDate,
        String taskId,
        String status,
        String payloadJson,
        long version,
        long planVersion) {
    public TaskPlanOccurrence {
        if (planId == null || planId.isBlank()) throw new IllegalArgumentException("planId is required");
        if (occurrenceDate == null) throw new IllegalArgumentException("occurrenceDate is required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (version < 1 || planVersion < 1) throw new IllegalArgumentException("versions must be positive");
    }

    public String occurrenceId() { return TaskRecurrence.occurrenceId(planId, occurrenceDate); }

    public boolean skipped() { return "SKIPPED".equals(status); }

    public boolean overridden() { return "OVERRIDDEN".equals(status); }
}
