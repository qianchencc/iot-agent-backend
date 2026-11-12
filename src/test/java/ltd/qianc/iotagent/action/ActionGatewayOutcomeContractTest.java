package ltd.qianc.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ActionGatewayOutcomeContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RequestIdentity IDENTITY = new RequestIdentity("Bearer outcome-fixture", "OUTCOME", IotSourceEnum.COMPANY);

    @Test
    void acceptedOneShotDoorDoesNotWaitForAnUnobservableDoorState() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response("{\"code\":0}")),
                states(Map.of(), true));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("door", "access", false)),
                actions("door", "access", Map.of("controlType", 2)));

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        assertThat(JSON.readTree(result.resultJson()).path("receipts").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(JSON.readTree(result.resultJson()).path("readbacks").isEmpty()).isTrue();
    }

    @Test
    void receiptOnlyAcDispatchDoesNotReadOrWaitForDeviceState() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response("{\"code\":0}")),
                states(Map.of(), true));
        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)),
                actions("ac", "ac", Map.of("temperature", 27)),
                Map.of("ac", Map.of("power", "on", "temperature", 26.5)), false);
        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        assertThat(JSON.readTree(result.resultJson()).path("receipts").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(JSON.readTree(result.resultJson()).path("readbacks").isEmpty()).isTrue();
    }

    @Test
    void explicitBusinessRefusalIsNotAnUncertainDispatch() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response("{\"code\":0,\"content\":{\"code\":422,\"message\":\"rejected\"}}")),
                states(Map.of(), true));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("light", "lighting", false)),
                actions("light", "lighting", Map.of("closeOrOpen", 0)));

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.REJECTED);
        assertThat(JSON.readTree(result.resultJson()).path("receipts").get(0).path("outcome").asText()).isEqualTo("REJECTED");
    }

    @Test
    void partialAcParametersRetainAcceptedAndRejectedEvidence() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        ActionCommandGateway gateway = gateway(request -> Mono.just(response(posts.getAndIncrement() == 0
                ? "{\"code\":0}" : "{\"code\":422,\"message\":\"temperature refused\"}")),
                states(Map.of("ac", Map.of("power", "on", "temperature", 24)), false));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)),
                actions("ac", "ac", Map.of("power", "on", "temperature", 24)),
                Map.of("ac", Map.of("power", "off", "temperature", 25)));
        JsonNode receipt = JSON.readTree(result.resultJson()).path("receipts").get(0);

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.PARTIALLY_ACCEPTED);
        assertThat(receipt.path("outcome").asText()).isEqualTo("PARTIALLY_ACCEPTED");
        assertThat(receipt.path("fields").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(receipt.path("fields").get(1).path("outcome").asText()).isEqualTo("REJECTED");
        assertThat(JSON.readTree(result.resultJson()).path("readbacks").isEmpty()).isTrue();
    }

    @Test
    void missingTemperatureCannotSkipDispatchOrConfirmAnAcTarget() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        ActionCommandGateway gateway = gateway(request -> { posts.incrementAndGet(); return Mono.just(response("{\"code\":0}")); },
                states(Map.of("ac", Map.of("power", "off")), false));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)),
                actions("ac", "ac", Map.of("temperature", 24)), Map.of("ac", Map.of("power", "off")));
        JsonNode evidence = JSON.readTree(result.resultJson());

        assertThat(posts.get()).isEqualTo(1);
        assertThat(evidence.path("receipts").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(evidence.path("readbacks").get(0).path("outcome").asText()).isEqualTo("unconfirmed");
    }

    @Test
    void acceptedAndRefusedDevicesAreNotReportedAsAllAccepted() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response(request.url().getPath().contains("access")
                ? "{\"code\":422}" : "{\"code\":0}")), states(Map.of("light", false), false));
        String selected = JSON.writeValueAsString(List.of(
                action("light", "lighting", Map.of("closeOrOpen", 0)), action("door", "access", Map.of("controlType", 2))));

        ActionDispatchResult result = gateway.dispatch(IDENTITY,
                scope(device("light", "lighting", false), device("door", "access", false)), selected);
        JsonNode receipts = JSON.readTree(result.resultJson()).path("receipts");

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.PARTIALLY_ACCEPTED);
        assertThat(receipts.get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(receipts.get(1).path("outcome").asText()).isEqualTo("REJECTED");
    }

    @Test
    void staleAcReadbackCannotConfirmEvenWithoutPreDispatchState() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response("{\"code\":0}")),
                states(Map.of("ac", Map.of("power", "on")), false, "2020-01-01T00:00:00Z"));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)),
                actions("ac", "ac", Map.of("power", "on")));

        assertThat(JSON.readTree(result.resultJson()).path("readbacks").get(0).path("outcome").asText())
                .isEqualTo("unconfirmed");
    }

    @Test
    void freshMatchingAcStateConfirmsTheExecutedTarget() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.just(response("{\"code\":0}")),
                states(Map.of("ac", Map.of("power", "on")), false));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)),
                actions("ac", "ac", Map.of("power", "on")), Map.of("ac", Map.of("power", "off")));

        assertThat(JSON.readTree(result.resultJson()).path("readbacks").get(0).path("outcome").asText())
                .isEqualTo("confirmed");
    }

    @Test
    void ambiguousTransportFailureNeverBecomesSafelyNotDispatched() throws Exception {
        ActionCommandGateway gateway = gateway(request -> Mono.error(new IllegalStateException("connection lost")),
                states(Map.of(), true));
        String selected = actions("door", "access", Map.of("controlType", 2));

        assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(device("door", "access", false)), selected))
                .isInstanceOf(ActionDispatchException.class)
                .satisfies(error -> assertThat(((ActionDispatchException) error).dispatchStarted()).isTrue());
    }

    @Test
    void acAcceptanceBeforeCredentialFailureCannotRollBackToARepeatableDraft() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        ActionCommandGateway gateway = gateway(request -> Mono.just(posts.getAndIncrement() == 0
                ? response("{\"code\":0}") : ClientResponse.create(HttpStatus.FORBIDDEN).build()), states(Map.of(), true));
        String selected = actions("ac", "ac", Map.of("power", "on", "temperature", 24));

        assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(device("ac", "ac", false)), selected))
                .isInstanceOf(ActionDispatchException.class)
                .satisfies(error -> assertThat(((ActionDispatchException) error).dispatchStarted()).isTrue());
    }

    @Test
    void partialAcceptanceBeforeAnotherDevicesCredentialFailureCannotBecomeRepeatable() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        ActionCommandGateway gateway = gateway(request -> Mono.just(switch (posts.getAndIncrement()) {
            case 0 -> response("{\"code\":0}");
            case 1 -> response("{\"code\":422}");
            default -> ClientResponse.create(HttpStatus.FORBIDDEN).build();
        }), states(Map.of(), true));
        String selected = JSON.writeValueAsString(List.of(
                action("ac", "ac", Map.of("power", "on", "temperature", 24)),
                action("door", "access", Map.of("controlType", 2))));

        assertThatThrownBy(() -> gateway.dispatch(IDENTITY,
                scope(device("ac", "ac", false, "shared-hub"), device("door", "access", false, "shared-hub")), selected))
                .isInstanceOf(ActionDispatchException.class)
                .satisfies(error -> assertThat(((ActionDispatchException) error).dispatchStarted()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"environment", "meter", "video", "smoke", "flooding"})
    void observationOnlyDevicesNeverBecomeControlActions(String kind) throws Exception {
        ActionCommandGateway gateway = gateway(request -> { throw new AssertionError("read-only device dispatched"); }, states(Map.of(), true));
        String selected = actions("observation", kind, Map.of("power", "on"));

        assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(device("observation", kind, true)), selected))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可控");
    }

    @Test
    void mismatchedDeviceKindCannotUseADifferentControlEndpoint() throws Exception {
        ActionCommandGateway gateway = gateway(request -> { throw new AssertionError("wrong control endpoint dispatched"); }, states(Map.of(), true));
        String selected = actions("light", "access", Map.of("controlType", 2));

        assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(device("light", "lighting", false)), selected))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ActionCommandGateway gateway(ExchangeFunction exchange, IotAdapter iot) {
        return ActionCommandGatewayFactory.webClient(JSON, WebClient.builder().exchangeFunction(exchange).build(), iot,
                Duration.ofSeconds(1), "/lighting", "/access", "/ac", Duration.ZERO);
    }

    private static IotAdapter states(Map<String, Object> values, boolean unsupported) {
        return states(values, unsupported, "2035-01-01T00:00:00Z");
    }

    private static IotAdapter states(Map<String, Object> values, boolean unsupported, String observedAt) {
        return new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new AssertionError("gateway cannot load its own authorization");
            }
            @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> ids) {
                if (unsupported || ids.contains("door")) throw new AssertionError("door does not expose a verifiable open-once state");
                return Mono.just(new DeviceStateReadResult(values, observedAt));
            }
        };
    }

    private static AuthorizedDeviceScope scope(AuthorizedDevice... devices) {
        return AuthorizedDeviceScope.of(IDENTITY, IotSourceEnum.COMPANY, List.of(devices));
    }

    private static AuthorizedDevice device(String id, String kind, boolean observationOnly) {
        return device(id, kind, observationOnly, id + "-hub");
    }

    private static AuthorizedDevice device(String id, String kind, boolean observationOnly, String hubId) {
        return new AuthorizedDevice(id, kind, id, "fixture", observationOnly, id + "-control", id,
                hubId, "fixture-area", List.of(), List.of(), kind.equals("ac") ? Map.of("companyAcActionId", "ac-action") : Map.of());
    }

    private static Map<String, Object> action(String id, String kind, Map<String, Object> command) {
        return Map.of("id", "action-" + id, "deviceId", id, "kind", kind, "command", command);
    }

    private static String actions(String id, String kind, Map<String, Object> command) throws Exception {
        return JSON.writeValueAsString(List.of(action(id, kind, command)));
    }

    private static ClientResponse response(String body) {
        return ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, "application/json").body(body).build();
    }
}
