package ltd.qianc.iotagent.iot;

/** Persisted alarm facts. Ordinary reads and authorization never collect customer data. */
public interface SafetyHistory {
    reactor.core.publisher.Mono<ObservationResponse> read(ObservationRequest request);
    AuthorizedDeviceScope authorizedScope(RequestIdentity identity);
}
