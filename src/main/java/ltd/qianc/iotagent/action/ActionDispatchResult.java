package ltd.qianc.iotagent.action;

import java.util.Objects;

/**
 * Adapter result. {@code dispatchStarted} is retained separately because an
 * exception after dispatch cannot be reported as safely not-dispatched.
 */
public record ActionDispatchResult(
        ActionDraftStatus status,
        String resultJson,
        boolean dispatchStarted,
        ActionReceiptOutcome receiptOutcome) {

    public ActionDispatchResult(ActionDraftStatus status, String resultJson, boolean dispatchStarted) {
        this(status, resultJson, dispatchStarted, null);
    }

    public ActionDispatchResult {
        Objects.requireNonNull(status, "status");
        if (!status.terminal() || status == ActionDraftStatus.CANCELLED || status == ActionDraftStatus.EXPIRED) {
            throw new IllegalArgumentException("Gateway result must be an execution terminal status");
        }
        resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson;
        receiptOutcome = receiptOutcome == null ? ActionReceiptOutcome.UNKNOWN : receiptOutcome;
    }
}
