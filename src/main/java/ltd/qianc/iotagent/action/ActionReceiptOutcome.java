package ltd.qianc.iotagent.action;

/** Device-side evidence is distinct from gateway acceptance. */
public enum ActionReceiptOutcome {
    ACCEPTED,
    DEVICE_APPLIED,
    TIMEOUT,
    UNKNOWN,
    REJECTED,
    NOT_DISPATCHED,
    ALREADY_SATISFIED
}
