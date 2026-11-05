package ltd.qianc.iotagent.action;

/** Projects a completed Office action into durable task history; never dispatches devices. */
@FunctionalInterface
public interface ImmediateActionHistory {
    void record(ActionDraftFact completed, ltd.qianc.iotagent.iot.AuthorizedDeviceScope scope);
}
