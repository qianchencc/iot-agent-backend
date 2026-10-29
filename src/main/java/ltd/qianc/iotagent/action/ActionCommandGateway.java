package ltd.qianc.iotagent.action;

import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.RequestIdentity;

/**
 * Explicit seam for the customer control protocol. The action module does not
 * invent a production endpoint or schema; production wiring is fail-closed.
 * A Runtime-derived local fixture may be selected only by explicit control mode.
 */
public interface ActionCommandGateway {

    ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson);

}
