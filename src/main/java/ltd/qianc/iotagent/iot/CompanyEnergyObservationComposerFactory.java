package ltd.qianc.iotagent.iot;

import java.time.Clock;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl;

public final class CompanyEnergyObservationComposerFactory {
    private CompanyEnergyObservationComposerFactory() {
    }

    /** Uses the bounded default budget suitable for dashboard reads. */
    public static CompanyEnergyObservationComposer composed(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock) {
        return new CompanyEnergyObservationComposerImpl(rawHistory, iot, clock);
    }


    /** Test and deployment seam for making the read budget explicit. */
    public static CompanyEnergyObservationComposer composed(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock,
            int maxPagesPerMeter, int maxRows) {
        return new CompanyEnergyObservationComposerImpl(rawHistory, iot, clock, maxPagesPerMeter, maxRows);
    }

}
