package ltd.qianc.iotagent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompanyControlGatewayContractTest {
    private static final RequestIdentity IDENTITY = new RequestIdentity("Bearer company-user", "TENANT-1", IotSource.COMPANY);

    @Test
    void lightingUsesRuntimeCompanyEndpointAndAuthorizedBindingWithReadback() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readDeviceStates(IDENTITY, List.of("light-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of("light-1", true), "2026-10-31T09:00:00Z")));
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":200,\"content\":{\"code\":0,\"status\":\"ok\"}}"));
        }, iot);
        AuthorizedDevice light = new AuthorizedDevice("light-1", "lighting", "大厅照明", "一层", false,
                "light-control-1", "light-1", "hub-light", "1-2-3", List.of(), List.of(), Map.of());

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":0}}]");

        assertThat(result.status()).isEqualTo(ActionDraftStatus.ACCEPTED);
        assertThat(request.get().url().getPath()).isEqualTo("/micro-api/api/office/app/device/lighting/down");
        assertThat(request.get().body()).isNotNull();
        assertThat(result.resultJson()).contains("unconfirmed");
    }

    @Test
    void companyAcUsesVerifiedInteractiveBindingAndPowerOnType129() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readDeviceStates(IDENTITY, List.of("ac-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of("ac-1", Map.of("power", "on")), "2026-10-31T09:00:00Z")));
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":0,\"content\":{\"code\":0,\"status\":\"ok\"}}"));
        }, iot);
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                "provider-ac", "ac-1", "hub-ac", "1-2-3", List.of(), List.of(), Map.of("companyAcActionId", "air-condition-action"));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(ac),
                "[{\"id\":\"action-1\",\"deviceId\":\"ac-1\",\"kind\":\"ac\",\"command\":{\"power\":\"on\"}}]");

        assertThat(result.status()).isEqualTo(ActionDraftStatus.ACCEPTED);
        assertThat(request.get().url().getPath()).isEqualTo("/micro-api/api/iot-center/interactive/hub-ac/air-condition-action/down");
        assertThat(result.resultJson()).contains("confirmed");
    }

    @Test
    void controlCannotUseADeviceOutsideTheFreshCompanyScope() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":0}"));
        }, iot);
        AuthorizedDevice allowed = new AuthorizedDevice("allowed", "lighting", "授权灯", "一层", false,
                "allowed-control", "allowed", "hub", "leaf", List.of(), List.of(), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(allowed),
                "[{\"id\":\"action-1\",\"deviceId\":\"outside\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":0}}]"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request.get()).isNull();
    }
    @Test
    void upstreamAuthorizationFailureRemainsTyped() {
        IotAdapter iot = mock(IotAdapter.class);
        ActionCommandGateway gateway = gateway(candidate -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build()), iot);
        AuthorizedDevice light = new AuthorizedDevice("light-1", "lighting", "大厅照明", "一层", false,
                "light-control-1", "light-1", "hub-light", "1-2-3", List.of(), List.of(), Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":0}}]"))
                .isInstanceOf(GatewayAuthorizationException.class)
                .extracting(error -> ((GatewayAuthorizationException) error).upstreamStatus())
                .isEqualTo(403);
    }


    private static AuthorizedDeviceScope scope(AuthorizedDevice... devices) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return IDENTITY; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return List.of(devices); }
        };
    }

    private static ActionCommandGateway gateway(ExchangeFunction exchange, IotAdapter iot) {
        return ActionCommandGatewayFactory.webClient(new ObjectMapper(), WebClient.builder().exchangeFunction(exchange).build(), iot,
                Duration.ofSeconds(1), "/micro-api/api/office/app/device/lighting/down",
                "/micro-api/api/office/app/device/access/down", "/micro-api/api/office/app/device/ac/down");
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }
}
