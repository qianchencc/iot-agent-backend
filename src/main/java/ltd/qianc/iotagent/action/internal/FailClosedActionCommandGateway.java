package ltd.qianc.iotagent.action.internal;

import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDraftUnavailableException;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** No customer control schema is assumed until an explicit adapter is configured. */
public final class FailClosedActionCommandGateway implements ActionCommandGateway {
    private final String reason;

    public FailClosedActionCommandGateway(String reason) {
        this.reason = reason;
    }

    @Override
    public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson) {
        throw new ActionDraftUnavailableException(reason);
    }
}
