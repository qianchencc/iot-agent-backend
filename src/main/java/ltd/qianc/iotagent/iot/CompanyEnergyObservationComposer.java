package ltd.qianc.iotagent.iot;

import java.util.Objects;
import reactor.core.publisher.Mono;

/**
 * Local, read-only Company energy business projection.
 *
 * <p>The composer owns aggregation but not HTTP routing. Callers pass the same
 * server-built request used by {@link ObservationAdapter}. It supports the
 * local Runtime-compatible Company energy projections: daily, weekly
 * comparison, monthly report, and scoped meter report.</p>
 */
public interface CompanyEnergyObservationComposer {

    /** Compose a report from the current authorization scope and raw history. */
    Mono<ObservationResponse> read(ObservationRequest request);

    /** Explicit alias for integration code that treats this seam as a composer. */
    default Mono<ObservationResponse> compose(ObservationRequest request) {
        return read(Objects.requireNonNull(request, "request"));
    }
}
