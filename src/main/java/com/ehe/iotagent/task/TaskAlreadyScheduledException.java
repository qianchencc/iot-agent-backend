package com.ehe.iotagent.task;

/**
 * Runtime pi-runner already_scheduled receipt: an enabled plan with the same
 * repeat rule, checkpoint times, control targets and read scopes already
 * exists, so the new proposal is not registered.
 */
public final class TaskAlreadyScheduledException extends RuntimeException {
    private final String planId;
    private final String label;
    private final String nextAt;

    public TaskAlreadyScheduledException(String planId, String label, String nextAt) {
        super("已有相同周期计划，本次未重复创建。需要调整时间、范围或停止时用 propose_task_change；不要重复确认同一安排。");
        this.planId = planId;
        this.label = label;
        this.nextAt = nextAt;
    }

    public String planId() {
        return planId;
    }

    public String label() {
        return label;
    }

    public String nextAt() {
        return nextAt;
    }
}
