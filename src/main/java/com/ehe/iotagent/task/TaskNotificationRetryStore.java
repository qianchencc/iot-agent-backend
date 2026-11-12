package com.ehe.iotagent.task;

import java.util.List;

/**
 * Durable compensation boundary for terminal-task notifications whose inbox
 * write failed. The terminal task fact and its phase results are already
 * durable; this queue only re-projects the inbox row. Recovery MUST NOT
 * dispatch any task phase or repeat a device action.
 */
public interface TaskNotificationRetryStore {

    /** Marks a terminal task whose notification row is still missing. */
    default void markPending(String taskId, String error) { }

    /** Terminal tasks whose compensation is still due, oldest update first. */
    default List<TaskFact> pending(int limit) { return List.of(); }

    default void failed(String taskId, String error) { }

    default void succeeded(String taskId) { }
}
