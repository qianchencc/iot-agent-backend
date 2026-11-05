package ltd.qianc.iotagent.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.iot.internal.JdbcDeviceCandidateSetStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Duration;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;
import ltd.qianc.iotagent.iot.internal.FailClosedIotAdapter;
import ltd.qianc.iotagent.iot.internal.WebClientIotAdapter;

/** Construction entry point; implementations remain internal to the IoT module. */
public final class IotAdapterFactory {

    private IotAdapterFactory() {
    }

    public static IotAdapter webClient(
            WebClient webClient,
            IotGatewayRoutes routes,
            Duration callTimeout,
            IotCallPolicy callPolicy) {
        return new WebClientIotAdapter(webClient, routes, callTimeout, callPolicy);
    }

    public static IotAdapter webClient(
            WebClient webClient,
            IotGatewayRoutes routes,
            Duration callTimeout,
            IotCallPolicy callPolicy,
            DeviceCandidateSetStore candidateStore) {
        return new WebClientIotAdapter(webClient, routes, callTimeout, callPolicy, candidateStore);
    }

    public static DeviceCandidateSetStore candidateSets(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcDeviceCandidateSetStore(jdbc, mapper);
    }

    public static IotAdapter failClosed(String reason) {
        return new FailClosedIotAdapter(Objects.requireNonNull(reason, "reason"));
    }
}
