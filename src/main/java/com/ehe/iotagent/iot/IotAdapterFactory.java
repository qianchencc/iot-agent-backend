package com.ehe.iotagent.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Objects;
import com.ehe.iotagent.iot.internal.LocalReadIotAdapter;
import com.ehe.iotagent.iot.internal.FailClosedIotAdapter;
import com.ehe.iotagent.iot.internal.JdbcDeviceCandidateSetStore;
import com.ehe.iotagent.iot.internal.WebClientIotAdapter;
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
    /** One ordinary read adapter for HTTP, Agent and business consumers; real actions are explicit. */
    public static IotAdapter localReads(IotAdapter source, java.util.function.Supplier<DeviceDirectory> directories,
            java.util.function.Supplier<DeviceObservations> observations, DeviceCandidateSetStore candidates) {
        return new LocalReadIotAdapter(source, directories, observations, candidates);
    }

    public static DeviceCandidateSetStore candidateSets(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcDeviceCandidateSetStore(jdbc, mapper);
    }

    public static IotAdapter failClosed(String reason) {
        return new FailClosedIotAdapter(Objects.requireNonNull(reason, "reason"));
    }
}
