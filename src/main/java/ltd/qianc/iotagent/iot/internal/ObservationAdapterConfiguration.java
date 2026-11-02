package ltd.qianc.iotagent.iot.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import ltd.qianc.iotagent.iot.AutomationSummaryReader;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapterFactory;
import ltd.qianc.iotagent.iot.ObservationGatewayRoutes;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposer;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposerFactory;
import ltd.qianc.iotagent.iot.CompanyEnergyPolicy;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.region.RegionSeenStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Customer read-only routes are explicit configuration. Defaults are limited to
 * paths and response envelopes copied from the Runtime observation reader:
 * environment/energy history, safety ledgers, and Office membership regions.
 * Company energy projections, including the Runtime-compatible energy report,
 * are composed from the configured energy-history seam; artifact protocols
 * remain unavailable until their exact customer contract is known.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationAdapterConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservationAdapter.class)
    ObservationAdapter observationAdapter(
            WebClient.Builder builder,
            Environment environment,
            IotAdapter iot,
            ObjectProvider<AutomationSummaryReader> automation,
            ObjectProvider<RegionSeenStore> regionSeen,
            ObjectProvider<CompanyEnergyPolicy> energyPolicy,
            ObjectProvider<ReadSnapshots> readSnapshots,
            @Value("${iot.gateway.call-timeout-ms:15000}") long timeoutMs,
            @Value("${iot.gateway.base-url:}") String baseUrl) {
        ObservationAdapter delegate;
        if (baseUrl == null || baseUrl.isBlank()) {
            delegate = ObservationAdapterFactory.failClosed("物联只读观察上游未配置");
        } else {
            EnumMap<ObservationOperation, String> configured = new EnumMap<>(ObservationOperation.class);
            configured.put(ObservationOperation.ENVIRONMENT_HISTORY,
                    route(environment, ObservationOperation.ENVIRONMENT_HISTORY,
                            "/micro-api/api/iot-center/env/history/page"));
            configured.put(ObservationOperation.ENERGY_HISTORY,
                    route(environment, ObservationOperation.ENERGY_HISTORY,
                            "/micro-api/api/energy/electricData/page"));
            configured.put(ObservationOperation.DASHBOARD,
                    route(environment, ObservationOperation.DASHBOARD, null));
            configured.put(ObservationOperation.SAFETY_EVENTS,
                    route(environment, ObservationOperation.SAFETY_EVENTS,
                            "/micro-api/api/perception/fire/{ledger}/alarm/page"));
            configured.put(ObservationOperation.SAFETY_EVENT,
                    route(environment, ObservationOperation.SAFETY_EVENT,
                            "/micro-api/api/perception/fire/{ledger}/alarm/page"));
            configured.put(ObservationOperation.SAFETY_EVENT_CAMERAS,
                    route(environment, ObservationOperation.SAFETY_EVENT_CAMERAS, null));
            configured.put(ObservationOperation.ENERGY_COMPARISON,
                    route(environment, ObservationOperation.ENERGY_COMPARISON, null));
            configured.put(ObservationOperation.ENERGY_REPORT,
                    route(environment, ObservationOperation.ENERGY_REPORT, null));
            configured.put(ObservationOperation.COMPANY_ENERGY_DAILY,
                    route(environment, ObservationOperation.COMPANY_ENERGY_DAILY, null));
            configured.put(ObservationOperation.COMPANY_ENERGY_REPORT,
                    route(environment, ObservationOperation.COMPANY_ENERGY_REPORT, null));
            configured.put(ObservationOperation.COMPANY_METER_REPORT,
                    route(environment, ObservationOperation.COMPANY_METER_REPORT, null));
            configured.put(ObservationOperation.VIDEO_STREAM,
                    route(environment, ObservationOperation.VIDEO_STREAM, null));
            configured.put(ObservationOperation.REPORT,
                    route(environment, ObservationOperation.REPORT, null));
            configured.put(ObservationOperation.REPORT_DOWNLOAD,
                    route(environment, ObservationOperation.REPORT_DOWNLOAD, null));
            configured.put(ObservationOperation.REGION_LIST,
                    route(environment, ObservationOperation.REGION_LIST,
                            "/micro-api/api/office/office/member/my/office"));
            configured.values().removeIf(path -> path == null || path.isBlank());
            try {
                delegate = ObservationAdapterFactory.webClient(
                        builder.baseUrl(baseUrl).build(),
                        new ObservationGatewayRoutes(Map.copyOf(configured)),
                        Duration.ofMillis(timeoutMs),
                        IotCallPolicy.passThrough());
            } catch (RuntimeException invalidConfiguration) {
                delegate = ObservationAdapterFactory.failClosed("物联只读观察上游配置无效");
            }
        }
        AutomationSummaryReader automationReader = automation.getIfAvailable(
                () -> identity -> Mono.error(new IllegalStateException("任务摘要读取适配器未配置")));
        Clock clock = Clock.systemUTC();
        CompanyEnergyPolicy configuredPolicy = energyPolicy.getIfAvailable();
        ReadSnapshots snapshots = readSnapshots.getIfAvailable();
        CompanyEnergyObservationComposer energyComposer = configuredPolicy == null
                ? CompanyEnergyObservationComposerFactory.composed(delegate, iot, clock, snapshots)
                : CompanyEnergyObservationComposerFactory.composed(delegate, iot, clock, configuredPolicy, snapshots);
        return ObservationAdapterFactory.composed(
                delegate, iot, automationReader, regionSeen.getIfAvailable(), energyComposer, snapshots, clock);
    }

    private static String route(Environment environment, ObservationOperation operation, String fallback) {
        String property = environment.getProperty("iot.observation.routes." + operation.name().toLowerCase().replace('_', '-'));
        return property == null || property.isBlank() ? fallback : property;
    }
}
