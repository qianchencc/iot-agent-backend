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
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
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
    /** Kept distinct from the ordinary ObservationAdapter bean so no consumer accidentally injects transport. */
    public record Source(ObservationAdapter adapter) { }

    @Bean
    Source observationSource(WebClient.Builder builder, Environment environment,
            @Value("${iot.gateway.call-timeout-ms:15000}") long timeoutMs,
            @Value("${iot.gateway.base-url:}") String baseUrl) {
        ObservationAdapter delegate;
        if (baseUrl == null || baseUrl.isBlank()) {
            delegate = ObservationAdapterFactory.failClosed("物联只读观察上游未配置");
        } else {
            EnumMap<ObservationOperationEnum, String> configured = new EnumMap<>(ObservationOperationEnum.class);
            configured.put(ObservationOperationEnum.ENVIRONMENT_HISTORY,
                    route(environment, ObservationOperationEnum.ENVIRONMENT_HISTORY,
                            "/micro-api/api/iot-center/env/history/page"));
            configured.put(ObservationOperationEnum.ENERGY_HISTORY,
                    route(environment, ObservationOperationEnum.ENERGY_HISTORY,
                            "/micro-api/api/energy/electricData/page"));
            configured.put(ObservationOperationEnum.DASHBOARD,
                    route(environment, ObservationOperationEnum.DASHBOARD, null));
            configured.put(ObservationOperationEnum.SAFETY_EVENTS,
                    route(environment, ObservationOperationEnum.SAFETY_EVENTS,
                            "/micro-api/api/perception/fire/{ledger}/alarm/page"));
            configured.put(ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                    route(environment, ObservationOperationEnum.SAFETY_EVENT_CAMERAS, null));
            configured.put(ObservationOperationEnum.ENERGY_COMPARISON,
                    route(environment, ObservationOperationEnum.ENERGY_COMPARISON, null));
            configured.put(ObservationOperationEnum.ENERGY_REPORT,
                    route(environment, ObservationOperationEnum.ENERGY_REPORT, null));
            configured.put(ObservationOperationEnum.COMPANY_ENERGY_DAILY,
                    route(environment, ObservationOperationEnum.COMPANY_ENERGY_DAILY, null));
            configured.put(ObservationOperationEnum.COMPANY_ENERGY_REPORT,
                    route(environment, ObservationOperationEnum.COMPANY_ENERGY_REPORT, null));
            configured.put(ObservationOperationEnum.COMPANY_METER_REPORT,
                    route(environment, ObservationOperationEnum.COMPANY_METER_REPORT, null));
            configured.put(ObservationOperationEnum.VIDEO_STREAM,
                    route(environment, ObservationOperationEnum.VIDEO_STREAM, null));
            configured.put(ObservationOperationEnum.REPORT,
                    route(environment, ObservationOperationEnum.REPORT, null));
            configured.put(ObservationOperationEnum.REPORT_DOWNLOAD,
                    route(environment, ObservationOperationEnum.REPORT_DOWNLOAD, null));
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
        return new Source(delegate);
    }

    @Bean
    @ConditionalOnMissingBean(ObservationAdapter.class)
    ObservationAdapter observationAdapter(WebClient.Builder builder, Environment environment, IotAdapter iot,
            ObjectProvider<AutomationSummaryReader> automation, ObjectProvider<RegionSeenStore> regionSeen,
            ObjectProvider<CompanyEnergyPolicy> energyPolicy, ObjectProvider<ltd.qianc.iotagent.iot.EnergyHistory> histories,
            ObjectProvider<ltd.qianc.iotagent.iot.EnvironmentHistory> environments,
            ObjectProvider<ltd.qianc.iotagent.iot.SafetyHistory> safetyHistories, Source source,
            @Value("${iot.gateway.call-timeout-ms:15000}") long timeoutMs,
            @Value("${iot.gateway.base-url:}") String baseUrl) {
        ObservationAdapter delegate = source.adapter();
        AutomationSummaryReader automationReader = automation.getIfAvailable(
                () -> identity -> Mono.error(new IllegalStateException("任务摘要读取适配器未配置")));
        Clock clock = Clock.systemUTC();
        CompanyEnergyPolicy configuredPolicy = energyPolicy.getIfAvailable();
        var history = histories.getIfAvailable(UnavailableEnergyHistory::new);
        CompanyEnergyObservationComposer energyComposer = CompanyEnergyObservationComposerFactory.composed(history, clock, configuredPolicy);
        var safetyHistory = safetyHistories.getIfAvailable(() -> new ltd.qianc.iotagent.iot.SafetyHistory() {
            @Override public reactor.core.publisher.Mono<ltd.qianc.iotagent.iot.ObservationResponse> read(ltd.qianc.iotagent.iot.ObservationRequest request) {
                return reactor.core.publisher.Mono.error(new ltd.qianc.iotagent.error.BusinessException("SAFETY_HISTORY_UNAVAILABLE", "本地安全台账未配置", 503));
            }
            @Override public ltd.qianc.iotagent.iot.AuthorizedDeviceScope authorizedScope(ltd.qianc.iotagent.iot.RequestIdentity identity) {
                throw new ltd.qianc.iotagent.error.BusinessException("SAFETY_HISTORY_UNAVAILABLE", "本地安全台账未配置", 503);
            }
        });
        return ObservationAdapterFactory.composed(
                delegate, iot, automationReader, regionSeen.getIfAvailable(), energyComposer, safetyHistory::read, environments.getIfAvailable(), clock);
    }

    /**
     * HMAC key for safety-ledger continuation cursors. Falls back to the shared
     * admin.config-secret-key; without any secret the composer signs with an
     * ephemeral process key and restarts honestly invalidate outstanding cursors.
     */
    static byte[] cursorMacKey(Environment environment) {
        String secret = environment.getProperty("iot.observation.safety-cursor-secret");
        if (secret == null) return null;
        secret = secret.trim();
        if (secret.isEmpty()) return null;
        if (secret.matches("(?i)[0-9a-f]{64}")) {
            byte[] key = new byte[32];
            for (int index = 0; index < 32; index++) {
                key[index] = (byte) Integer.parseInt(secret.substring(index * 2, index * 2 + 2), 16);
            }
            return key;
        }
        byte[] key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return key.length >= 16 ? key : null;
    }


    private static String route(Environment environment, ObservationOperationEnum operation, String fallback) {
        String property = environment.getProperty("iot.observation.routes." + operation.name().toLowerCase().replace('_', '-'));
        return property == null || property.isBlank() ? fallback : property;
    }
}
