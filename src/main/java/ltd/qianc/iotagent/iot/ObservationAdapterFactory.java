package ltd.qianc.iotagent.iot;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import ltd.qianc.iotagent.iot.internal.CompanyOfficeObservationAdapter;
import ltd.qianc.iotagent.iot.internal.FailClosedObservationAdapter;
import ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer;
import ltd.qianc.iotagent.iot.internal.WebClientObservationAdapter;
import ltd.qianc.iotagent.region.RegionSeenStore;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
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

    /** Builds the local Company dashboard and Office region composition boundary. */
    public static ObservationAdapter composed(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            Clock clock) {
        return new CompanyOfficeObservationAdapter(upstream, iot, automation, regionSeen, clock);
    }
    /** Builds the composition boundary with the optional Company energy projection. */
    public static ObservationAdapter composed(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            CompanyEnergyObservationComposer energyComposer,
            Clock clock) {
        return new CompanyOfficeObservationAdapter(upstream, iot, automation, regionSeen, energyComposer, clock);
    }
    public static ObservationAdapter composed(
            ObservationAdapter upstream, IotAdapter iot, AutomationSummaryReader automation,
            RegionSeenStore regionSeen, CompanyEnergyObservationComposer energyComposer,
            ReadSnapshots snapshots, Clock clock) {
        return new CompanyOfficeObservationAdapter(upstream, iot, automation, regionSeen,
                energyComposer, null, snapshots, clock);
    }

    /** Safety history and event resolution share the same source-bound authorization. */
    public static ObservationAdapter safetyLedger(ObservationAdapter upstream, IotAdapter iot,
            java.util.function.BiFunction<RequestIdentity, String,
                    reactor.core.publisher.Mono<java.util.Map<String, Object>>> metadata, Clock clock) {
        return safetyLedger(upstream, iot, metadata, null, clock);
    }

    public static ObservationAdapter safetyLedger(ObservationAdapter upstream, IotAdapter iot,
            java.util.function.BiFunction<RequestIdentity, String,
                    reactor.core.publisher.Mono<java.util.Map<String, Object>>> metadata,
            byte[] cursorMacKey, Clock clock) {
        SafetyLedgerComposer ledger = new SafetyLedgerComposer(upstream, iot,
                metadata == null ? null : metadata::apply, cursorMacKey, clock);
        return request -> switch (request.operation()) {
            case SAFETY_EVENTS -> ledger.readLedger(request);
            case SAFETY_EVENT -> ledger.readEvent(request);
            default -> reactor.core.publisher.Mono.error(
                    new ObservationUnavailableException("该入口仅支持安全台账和事件详情"));
        };
    }

    public static ObservationAdapter composed(
            ObservationAdapter upstream, IotAdapter iot, AutomationSummaryReader automation,
            RegionSeenStore regionSeen, CompanyEnergyObservationComposer energyComposer,
            ObservationAdapter safetyLedger, ReadSnapshots snapshots, Clock clock) {
        return new CompanyOfficeObservationAdapter(upstream, iot, automation, regionSeen,
                energyComposer, safetyLedger, snapshots, clock);
    }
}
