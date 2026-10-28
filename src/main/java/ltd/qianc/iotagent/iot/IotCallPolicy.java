package ltd.qianc.iotagent.iot;

import reactor.core.publisher.Mono;

/**
 * Reactive call-policy seam. A Resilience4j CircuitBreaker/Retry adapter can wrap
 * the supplied Mono without changing the gateway contract; cancellation remains
 * owned by the subscriber and is not converted into a successful result.
 */
@FunctionalInterface
public interface IotCallPolicy {

    <T> Mono<T> execute(String operation, Mono<T> call);

    static IotCallPolicy passThrough() {
        return new IotCallPolicy() {
            @Override
            public <T> Mono<T> execute(String operation, Mono<T> call) {
                return call;
            }
        };
    }
}
