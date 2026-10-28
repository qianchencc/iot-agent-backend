package ltd.qianc.iotagent.iot.internal;

import java.util.Objects;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.ReportDownload;
import reactor.core.publisher.Mono;

/** No observation payload is invented while upstream route schemas are unavailable. */
public final class FailClosedObservationAdapter implements ObservationAdapter {
    private final String reason;

    public FailClosedObservationAdapter(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.error(new ObservationUnavailableException(reason));
    }

    @Override
    public Mono<ReportDownload> download(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.error(new ObservationUnavailableException(reason));
    }
}
