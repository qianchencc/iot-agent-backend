package ltd.qianc.iotagent.iot;

import java.util.List;
import java.util.Optional;

/**
 * Server-derived device authorization. Implementations MUST only contain records
 * returned by the current upstream identity and MUST retain the source boundary.
 */
public interface AuthorizedDeviceScope {

    RequestIdentity identity();

    IotSource source();

    List<AuthorizedDevice> devices();

    default Optional<AuthorizedDevice> device(String id) {
        return devices().stream().filter(device -> device.id().equals(id)).findFirst();
    }
}
