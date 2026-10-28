package ltd.qianc.iotagent.iot;

import reactor.core.publisher.Mono;

/** Gateway boundary used by Agent tools; callers own subscription cancellation. */
public interface IotAdapter {

    Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity);
}
