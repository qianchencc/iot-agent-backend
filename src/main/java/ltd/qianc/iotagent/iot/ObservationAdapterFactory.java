package ltd.qianc.iotagent.iot;

import java.util.Objects;
import ltd.qianc.iotagent.iot.internal.FailClosedObservationAdapter;
import java.time.Duration;
import ltd.qianc.iotagent.iot.internal.WebClientObservationAdapter;
import org.springframework.web.reactive.function.client.WebClient;

/** Construction entry point for observation adapters; implementations stay internal. */
public final class ObservationAdapterFactory {
    private ObservationAdapterFactory() {
    }

    public static ObservationAdapter failClosed(String reason) {
        return new FailClosedObservationAdapter(Objects.requireNonNull(reason, "reason"));
    }
    public static ObservationAdapter webClient(
            WebClient webClient,
            ObservationGatewayRoutes routes,
            Duration timeout,
            IotCallPolicy callPolicy) {
        return new WebClientObservationAdapter(webClient, routes, timeout, callPolicy);
    }
}
