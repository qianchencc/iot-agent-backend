package com.ehe.iotagent.action.internal;

import com.ehe.iotagent.action.ActionCommandGateway;
import com.ehe.iotagent.action.ActionDraftUnavailableException;
import com.ehe.iotagent.action.ActionDispatchResult;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.RequestIdentity;

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
