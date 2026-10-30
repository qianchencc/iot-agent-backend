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
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.control.mode", havingValue = "fixture")
    IotAdapter fixtureIotAdapter() {
        return new FixtureIotAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(IotAdapter.class)
    IotAdapter iotAdapter(
            WebClient.Builder builder,
            @Value("${iot.gateway.base-url:}") String baseUrl,
            @Value("${iot.gateway.office-scope-path:}") String officePath,
            @Value("${iot.gateway.company-scope-path:}") String companyPath,
            @Value("${iot.gateway.office-membership-path:/micro-api/api/office/office/member/my/office}") String membershipPath,
            @Value("${iot.gateway.office-ac-path:/micro-api/api/office/app/device/ac/myOffice/pageByAc}") String acPath,
            @Value("${iot.gateway.office-lighting-path:/micro-api/api/office/device/page}") String lightingPath,
            @Value("${iot.gateway.office-access-path:/micro-api/api/office/device/page}") String accessPath,
            @Value("${iot.gateway.office-video-path:/micro-api/api/office/device/page}") String videoPath,
            @Value("${iot.gateway.office-ac-association-path:/micro-api/api/office/acAreaDevice/page}") String acAssociationPath,
            @Value("${iot.gateway.office-lighting-association-path:/micro-api/api/office/lightingAreaDevice/page}") String lightingAssociationPath,
            @Value("${iot.gateway.office-access-association-path:/micro-api/api/office/accessAreaDevice/page}") String accessAssociationPath,
            @Value("${iot.gateway.company-environment-path:/micro-api/api/iot-center/device/page}") String companyEnvironmentPath,
            @Value("${iot.gateway.company-energy-path:/micro-api/api/energy/equipment/page}") String companyEnergyPath,
            @Value("${iot.gateway.company-safety-path:/micro-api/api/perception/device/page}") String companySafetyPath,
            @Value("${iot.gateway.company-safety-detail-path:/micro-api/api/iot-center/device}") String companySafetyDetailPath,
            @Value("${iot.gateway.company-smart-base-path:/micro-api/api/office/app/device}") String companySmartBasePath,
            @Value("${iot.gateway.company-ac-area-path:/micro-api/api/office/app/device/ac/myAreaList}") String companyAcAreaPath,
            @Value("${iot.gateway.company-ac-device-path:/micro-api/api/office/app/device/ac/devicePage}") String companyAcDevicePath,
            @Value("${iot.gateway.company-ac-binding-path:/micro-api/api/iot-center/device/page}") String companyAcBindingPath,
            @Value("${iot.gateway.company-monitor-down-path:/micro-api/api/office/app/device/monitor/down}") String companyMonitorDownPath,
            @Value("${iot.gateway.proxy:}") String proxy,
            @Value("${iot.gateway.call-timeout-ms:30000}") long timeoutMs) {
        if (officePath == null || officePath.isBlank()) {
            return IotAdapterFactory.failClosed("物联 Office 授权设备范围路径未配置");
        }
        if ((companyEnvironmentPath == null || companyEnvironmentPath.isBlank())
                || (companyEnergyPath == null || companyEnergyPath.isBlank())
                || (companySafetyPath == null || companySafetyPath.isBlank())
                || (companySmartBasePath == null || companySmartBasePath.isBlank())
                || (companyAcAreaPath == null || companyAcAreaPath.isBlank())
                || (companyAcDevicePath == null || companyAcDevicePath.isBlank())
                || (companyAcBindingPath == null || companyAcBindingPath.isBlank())) {
            return IotAdapterFactory.failClosed("物联 Company 授权设备目录路径未配置");
        }
        try {
            WebClient.Builder gatewayBuilder = builder;
            if (proxy != null && !proxy.isBlank()) {
                java.net.URI proxyUri = java.net.URI.create(proxy.trim());
                if (!"http".equalsIgnoreCase(proxyUri.getScheme()) || proxyUri.getHost() == null || proxyUri.getPort() <= 0) {
                    throw new IllegalArgumentException("iot.gateway.proxy must be an HTTP proxy URL with host and port");
                }
                reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                        .proxy(spec -> spec.type(reactor.netty.transport.ProxyProvider.Proxy.HTTP)
                                .host(proxyUri.getHost()).port(proxyUri.getPort()));
                gatewayBuilder = builder.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));
            }
            return IotAdapterFactory.webClient(
                    gatewayBuilder.baseUrl(baseUrl).build(),
                    new IotGatewayRoutes(officePath, companyPath, membershipPath,
                            IotGatewayRoutes.runtimeOfficeSources(acPath, lightingPath, accessPath, videoPath,
                                    acAssociationPath, lightingAssociationPath, accessAssociationPath),
                            companyEnvironmentPath, companyEnergyPath, companySafetyPath, companySafetyDetailPath,
                            companySmartBasePath, companyAcAreaPath, companyAcDevicePath, companyAcBindingPath,
                            companyMonitorDownPath),
                    Duration.ofMillis(timeoutMs),
                    IotCallPolicy.passThrough());
        } catch (RuntimeException invalidConfiguration) {
            return IotAdapterFactory.failClosed("物联上游配置无效");
        }
    }
}
