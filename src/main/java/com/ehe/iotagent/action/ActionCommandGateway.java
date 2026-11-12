package com.ehe.iotagent.action;

import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.RequestIdentity;

/**
 * Explicit seam for the customer control protocol. The action module does not
 * invent a production endpoint or schema; production wiring is fail-closed.
 * A Runtime-derived local fixture may be selected only by explicit control mode.
 */
public interface ActionCommandGateway {

    ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson);

    /**
     * Dispatches actions after the confirmation path has performed its fresh
     * pre-dispatch state read. Implementations that do not need that evidence
     * retain the original seam.
     */
    default ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                          String selectedActionsJson, java.util.Map<String, Object> observedStates) {
        return dispatch(identity, scope, selectedActionsJson, observedStates, true);
    }

    /** Conditional AC tasks complete on accepted receipts; other controls retain readback. */
    default ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                          String selectedActionsJson, java.util.Map<String, Object> observedStates,
                                          boolean verifyReadback) {
        return dispatch(identity, scope, selectedActionsJson);
    }

}
