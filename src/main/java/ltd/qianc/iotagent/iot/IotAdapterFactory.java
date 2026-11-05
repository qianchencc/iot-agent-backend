package ltd.qianc.iotagent.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import ltd.qianc.iotagent.iot.internal.AgentReadIotAdapter;
import ltd.qianc.iotagent.iot.internal.FailClosedIotAdapter;
import ltd.qianc.iotagent.iot.internal.JdbcDeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.internal.WebClientIotAdapter;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;

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
    /** Agent ordinary reads reuse the existing backend read entries; actions stay on the source adapter. */
    public static IotAdapter agentReads(IotAdapter source, ReadSnapshots snapshots, ObjectMapper mapper) {
        return new AgentReadIotAdapter(source, snapshots, mapper);
    }

    public static DeviceCandidateSetStore candidateSets(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcDeviceCandidateSetStore(jdbc, mapper);
    }

    public static IotAdapter failClosed(String reason) {
        return new FailClosedIotAdapter(Objects.requireNonNull(reason, "reason"));
    }
}
