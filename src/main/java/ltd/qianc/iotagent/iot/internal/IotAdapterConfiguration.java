package ltd.qianc.iotagent.iot.internal;

import java.time.Duration;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotAdapterFactory;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotGatewayRoutes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Missing gateway configuration is an explicit 503 boundary, never an in-memory fallback. */
@Configuration(proxyBeanMethods = false)
public class IotAdapterConfiguration {

    @Bean
    @ConditionalOnMissingBean(IotAdapter.class)
    IotAdapter iotAdapter(
            WebClient.Builder builder,
            @Value("${iot.gateway.base-url:}") String baseUrl,
            @Value("${iot.gateway.office-scope-path:}") String officePath,
            @Value("${iot.gateway.company-scope-path:}") String companyPath,
            @Value("${iot.gateway.call-timeout-ms:10000}") long timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return IotAdapterFactory.failClosed("物联上游未配置");
        }
        if (officePath == null || officePath.isBlank() || companyPath == null || companyPath.isBlank()) {
            return IotAdapterFactory.failClosed("物联授权设备范围路径未配置");
        }
        try {
            return IotAdapterFactory.webClient(
                    builder.baseUrl(baseUrl).build(),
                    new IotGatewayRoutes(officePath, companyPath),
                    Duration.ofMillis(timeoutMs),
                    IotCallPolicy.passThrough());
        } catch (RuntimeException invalidConfiguration) {
            return IotAdapterFactory.failClosed("物联上游配置无效");
        }
    }
}
