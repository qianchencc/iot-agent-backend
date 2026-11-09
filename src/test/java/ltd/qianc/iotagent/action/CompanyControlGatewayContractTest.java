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
import ltd.qianc.iotagent.iot.IotSourceEnum;
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
    private static final RequestIdentity IDENTITY = new RequestIdentity("Bearer company-user", "TENANT-1", IotSourceEnum.COMPANY);

    @Test
    void lightingUsesRuntimeCompanyEndpointAndAuthorizedBindingWithReadback() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readLiveDeviceStates(IDENTITY, List.of("light-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of("light-1", true), "2026-10-31T09:00:00Z")));
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":200,\"content\":{\"code\":0,\"status\":\"ok\"}}"));
        }, iot);
        AuthorizedDevice light = new AuthorizedDevice("light-1", "lighting", "大厅照明", "一层", false,
                "light-control-1", "light-1", "hub-light", "1-2-3", List.of(), List.of(), Map.of());

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":0}}]");

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        assertThat(request.get().url().getPath()).isEqualTo("/micro-api/api/office/app/device/lighting/down");
        assertThat(request.get().body()).isNotNull();
        assertThat(result.resultJson()).contains("unconfirmed");
    }

    @Test
    void companyAcUsesVerifiedInteractiveBindingAndPowerOnType129() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readLiveDeviceStates(IDENTITY, List.of("ac-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of("ac-1", Map.of("power", "on")), "2026-10-31T09:00:00Z")));
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":0,\"content\":{\"code\":0,\"status\":\"ok\"}}"));
        }, iot);
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                "provider-ac", "ac-1", "hub-ac", "1-2-3", List.of(), List.of(), Map.of("companyAcActionId", "air-condition-action"));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(ac),
                "[{\"id\":\"action-1\",\"deviceId\":\"ac-1\",\"kind\":\"ac\",\"command\":{\"power\":\"on\"}}]");

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        assertThat(request.get().url().getPath()).isEqualTo("/micro-api/api/iot-center/interactive/hub-ac/air-condition-action/down");
    }


    @Test
    void freshPreStateSkipsAlreadySatisfiedCommandWithoutCallingUpstream() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        IotAdapter iot = mock(IotAdapter.class);
        ActionCommandGateway gateway = gateway(candidate -> {
            request.set(candidate);
            return Mono.just(json("{\"code\":0}"));
        }, iot);
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                "provider-ac", "ac-1", "hub-ac", "1-2-3", List.of(), List.of(), Map.of());

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(ac),
                "[{\"id\":\"action-1\",\"deviceId\":\"ac-1\",\"kind\":\"ac\",\"command\":{\"power\":\"on\"}}]",
                Map.of("ac-1", Map.of("power", "on")));

        assertThat(request.get()).isNull();
        assertThat(result.resultJson()).contains("ALREADY_SATISFIED");
    }

    @Test
    void readbackBeforeCommandAtIsUnconfirmedEvenWhenStateMatches() {
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readLiveDeviceStates(IDENTITY, List.of("ac-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of("ac-1", Map.of("power", "on")),
                        "2020-01-01T00:00:00Z")));
        ActionCommandGateway gateway = ActionCommandGatewayFactory.webClient(new ObjectMapper(),
                WebClient.builder().exchangeFunction(candidate -> Mono.just(json("{\"code\":0}"))).build(), iot,
                Duration.ofSeconds(1), "/lighting", "/access", "/ac", Duration.ZERO);
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                "provider-ac", "ac-1", "hub-ac", "1-2-3", List.of(), List.of(), Map.of("companyAcActionId", "air"));

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(ac),
                "[{\"id\":\"action-1\",\"deviceId\":\"ac-1\",\"kind\":\"ac\",\"command\":{\"power\":\"on\"}}]",
                Map.of("ac-1", Map.of("power", "off")));

        assertThat(result.resultJson()).contains("\"outcome\":\"unconfirmed\"");
    }
    @Test
    void fractionalAndOverflowingCommandsCannotReachAnUpstreamDispatch() {
        IotAdapter iot = mock(IotAdapter.class);
        ActionCommandGateway gateway = gateway(candidate -> Mono.just(json("{\"code\":0}")), iot);
        AuthorizedDevice access = new AuthorizedDevice("door-1", "access", "门禁", "一层", false,
                "door-control", "door-1", "hub-door", "1-2-3", List.of(), List.of(), Map.of());
        AuthorizedDevice light = new AuthorizedDevice("light-1", "lighting", "大厅照明", "一层", false,
                "light-control-1", "light-1", "hub-light", "1-2-3", List.of(), List.of(), Map.of());

        // 2.5/溢出值此前会经 asInt 截断成 2 下发；现在与草案校验同规则拒绝。
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(access),
                "[{\"id\":\"action-1\",\"deviceId\":\"door-1\",\"kind\":\"access\",\"command\":{\"controlType\":2.5}}]"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(access),
                "[{\"id\":\"action-1\",\"deviceId\":\"door-1\",\"kind\":\"access\",\"command\":{\"controlType\":4294967298}}]"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":1.9}}]"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":4294967297}}]"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(light),
                "[{\"id\":\"action-1\",\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"power\":\"banana\"}}]"))
                .isInstanceOf(IllegalArgumentException.class);

        // ac 非法组合在下发前拦截：power:null 此前会按默认 off 实际下发关机。
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼", false,
                "ac-control-1", "ac-1", "hub-ac", "1-2-3", List.of(), List.of(), Map.of());
        for (String command : new String[] {
                "{\"power\":null}",
                "{\"power\":\"boost\",\"temperature\":24}",
                "{\"power\":\"off\",\"temperature\":26}",
                "{\"temperature\":26.5}",
                "{\"temperature\":4294967322}",
                "{\"humidity\":40}"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(ac),
                    "[{\"id\":\"action-1\",\"deviceId\":\"ac-1\",\"kind\":\"ac\",\"command\":" + command + "}]"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void accessOnlyAcceptsRuntimeOneShotControlType() {
        IotAdapter iot = mock(IotAdapter.class);
        ActionCommandGateway gateway = gateway(candidate -> Mono.just(json("{\"code\":0}")), iot);
        AuthorizedDevice access = new AuthorizedDevice("door-1", "access", "门禁", "一层", false,
                "door-control", "door-1", "hub-door", "1-2-3", List.of(), List.of(), Map.of());

        ActionDispatchResult accepted = gateway.dispatch(IDENTITY, scope(access),
                "[{\"id\":\"action-1\",\"deviceId\":\"door-1\",\"kind\":\"access\",\"command\":{\"controlType\":2}}]");
        assertThat(accepted.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.dispatch(IDENTITY, scope(access),
                "[{\"id\":\"action-1\",\"deviceId\":\"door-1\",\"kind\":\"access\",\"command\":{\"controlType\":2,\"count\":2}}]"))
                .isInstanceOf(IllegalArgumentException.class);
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
            @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
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
