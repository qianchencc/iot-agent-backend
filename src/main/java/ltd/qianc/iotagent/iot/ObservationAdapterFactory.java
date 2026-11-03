package ltd.qianc.iotagent.iot;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import ltd.qianc.iotagent.iot.internal.CompanyOfficeObservationAdapter;
import ltd.qianc.iotagent.iot.internal.FailClosedObservationAdapter;
import ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer;
import ltd.qianc.iotagent.iot.internal.WebClientObservationAdapter;
import ltd.qianc.iotagent.region.RegionSeenStore;
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

    /** Safety ledger with current metadata read through the authorized hub binding. */
    public static ObservationAdapter safetyLedger(ObservationAdapter upstream, IotAdapter iot,
            java.util.function.BiFunction<RequestIdentity, String,
                    reactor.core.publisher.Mono<java.util.Map<String, Object>>> metadata,
            Clock clock) {
        return new SafetyLedgerComposer(upstream, iot, metadata::apply, clock)::readLedger;
    }

    /** Builds the composition boundary with an explicit safety ledger composer. */
    public static ObservationAdapter composed(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            CompanyEnergyObservationComposer energyComposer,
            SafetyLedgerComposer safetyLedger,
            Clock clock) {
        return new CompanyOfficeObservationAdapter(upstream, iot, automation, regionSeen,
                energyComposer, safetyLedger, clock);
    }
}
