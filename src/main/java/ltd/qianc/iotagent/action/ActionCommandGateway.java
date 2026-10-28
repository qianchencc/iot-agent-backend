package ltd.qianc.iotagent.action;

import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.RequestIdentity;

/**
 * Explicit seam for the customer control protocol. The action module does not
 * invent an upstream endpoint or schema; production wiring is fail-closed until
 * a protocol adapter is supplied.
 */
public interface ActionCommandGateway {

    ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson);
}
