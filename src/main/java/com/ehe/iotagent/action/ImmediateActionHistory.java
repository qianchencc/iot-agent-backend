package com.ehe.iotagent.action;

/** Projects a completed Office action into durable task history; never dispatches devices. */
@FunctionalInterface
public interface ImmediateActionHistory {
    void record(ActionDraftFact completed, com.ehe.iotagent.iot.AuthorizedDeviceScope scope);
}
