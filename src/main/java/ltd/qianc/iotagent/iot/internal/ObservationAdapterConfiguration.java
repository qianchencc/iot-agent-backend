package ltd.qianc.iotagent.iot.internal;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapterFactory;
import ltd.qianc.iotagent.iot.ObservationGatewayRoutes;
import ltd.qianc.iotagent.iot.ObservationOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Customer read-only routes are explicit configuration. Only the two routes
 * present in the Runtime observation source have safe defaults; report/video
 * schemas remain unavailable until their exact customer contract is configured.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservationAdapter.class)
    ObservationAdapter observationAdapter(
            WebClient.Builder builder,
            Environment environment,
            @Value("${iot.gateway.base-url:}") String baseUrl,
            @Value("${iot.gateway.call-timeout-ms:15000}") long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return ObservationAdapterFactory.failClosed("物联只读观察上游未配置");
        }
        EnumMap<ObservationOperation, String> configured = new EnumMap<>(ObservationOperation.class);
        configured.put(ObservationOperation.ENVIRONMENT_HISTORY,
                route(environment, ObservationOperation.ENVIRONMENT_HISTORY,
                        "/micro-api/api/iot-center/env/history/page"));
        configured.put(ObservationOperation.ENERGY_HISTORY,
                route(environment, ObservationOperation.ENERGY_HISTORY,
                        "/micro-api/api/energy/electricData/page"));
        for (ObservationOperation operation : ObservationOperation.values()) {
            String path = route(environment, operation, null);
            if (path != null) configured.put(operation, path);
        }
        configured.values().removeIf(path -> path == null || path.isBlank());
        try {
            return ObservationAdapterFactory.webClient(
                    builder.baseUrl(baseUrl).build(),
                    new ObservationGatewayRoutes(Map.copyOf(configured)),
                    Duration.ofMillis(timeoutMs),
                    IotCallPolicy.passThrough());
        } catch (RuntimeException invalidConfiguration) {
            return ObservationAdapterFactory.failClosed("物联只读观察上游配置无效");
        }
    }

    private static String route(Environment environment, ObservationOperation operation, String fallback) {
        String property = environment.getProperty("iot.observation.routes." + operation.name().toLowerCase().replace('_', '-'));
        return property == null || property.isBlank() ? fallback : property;
    }
}
