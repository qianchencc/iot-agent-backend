package ltd.qianc.iotagent.iot;

import java.time.Clock;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl;

public final class CompanyEnergyObservationComposerFactory {
    private CompanyEnergyObservationComposerFactory() { }

    /** All calculations consume the same local raw samples and dedicated authorization. */
    public static CompanyEnergyObservationComposer composed(EnergyHistory history, Clock clock) {
        return composed(history, clock, null);
    }

    /** Retains the explicitly confirmed unit and multiplier policy; persistence does not authorize conversion. */
    public static CompanyEnergyObservationComposer composed(EnergyHistory history, Clock clock, CompanyEnergyPolicy policy) {
        return new CompanyEnergyObservationComposerImpl(history, clock, policy);
    }
}
