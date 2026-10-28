package ltd.qianc.iotagent.iot;

import java.util.Objects;
import ltd.qianc.iotagent.iot.internal.FailClosedObservationAdapter;

/** Construction entry point for observation adapters; implementations stay internal. */
public final class ObservationAdapterFactory {
    private ObservationAdapterFactory() {
    }

    public static ObservationAdapter failClosed(String reason) {
        return new FailClosedObservationAdapter(Objects.requireNonNull(reason, "reason"));
    }
}
