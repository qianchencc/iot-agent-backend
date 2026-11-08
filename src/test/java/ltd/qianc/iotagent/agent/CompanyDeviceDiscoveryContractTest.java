package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.IotAdapterFactory;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotGatewayRoutes;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class CompanyDeviceDiscoveryContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"scopeText", "scopeTerms"})
    void companyScopeReturnsAllAuthorizedAirConditioners(String field) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("kind", "ac");
        arguments.put(field, field.equals("scopeTerms") ? List.of("公司") : "公司");
        var result = bridge(false).execute(request(arguments));
        assertThat(deviceIds(result)).containsExactly("ac-a", "ac-b");
        assertThat(result).containsEntry("totalMatches", 2).containsEntry("membersComplete", true);
    }

    @Test
    void omittedLocationWithDescriptionBrowsesAuthorizedType() {
        var result = bridge(false).execute(request(Map.of("kind", "ac", "deviceText", "空调")));
        assertThat(deviceIds(result)).containsExactly("ac-a", "ac-b");
    }

    @Test
    void physicalLocationStillNarrowsCompanyScope() {
        var result = bridge(false).execute(request(Map.of("kind", "ac", "scopeTerms", List.of("公司", "26楼"))));
        assertThat(deviceIds(result)).containsExactly("ac-a");
        assertThat(result).containsEntry("totalMatches", 1);
    }

    @Test
    void missingSearchIntentIsAParameterErrorNotAnUpstreamOutage() {
        var error = catchThrowableOfType(() -> bridge(false).execute(request(Map.of("kind", "ac"))), BusinessException.class);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("INVALID_DEVICE_QUERY");
        assertThat(error.httpStatus()).isEqualTo(400);
    }

    @Test
    void actualUpstreamFailureRemainsUnavailable() {
        var error = catchThrowableOfType(() -> bridge(true).execute(request(Map.of("kind", "ac", "deviceText", "空调"))), BusinessException.class);
        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo("IOT_UPSTREAM_UNAVAILABLE");
        assertThat(error.httpStatus()).isEqualTo(503);
    }

    private AgentToolBridge bridge(boolean unavailable) {
        var conversations = mock(ConversationFacade.class);
        var principal = new PrincipalInfo("Bearer fixture", "TEST-TENANT", "test-owner", "fixture");
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(principal, "run-1", "conversation-1", "turn-1", "company", null, Map.of()));
        var saved = new ConcurrentHashMap<String, DeviceCandidateRecord>();
        DeviceCandidateSetStore store = new DeviceCandidateSetStore() {
            @Override public DeviceCandidateRecord save(DeviceCandidateRecord record) {
                saved.put(record.candidateSetId(), record);
                return record;
            }
            @Override public Optional<DeviceCandidateRecord> find(String id, String conversationId) {
                return Optional.ofNullable(saved.get(id)).filter(record -> conversationId.equals(record.conversationId()));
            }
        };
        var client = WebClient.builder().exchangeFunction(ignored -> Mono.just(unavailable
                ? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()
                : ClientResponse.create(HttpStatus.OK).header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""
                        {"code":200,"content":[
                          {"id":"ac-a","kind":"ac","name":"测试空调甲","installAddress":"26楼测试区"},
                          {"id":"ac-b","kind":"ac","name":"测试空调乙","installAddress":"27楼测试区"},
                          {"id":"light-a","kind":"lighting","name":"测试灯具","installAddress":"26楼测试区"}
                        ]}
                        """).build())).build();
        var iot = IotAdapterFactory.webClient(client, new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough(), store);
        return AgentToolBridgeFactory.local(conversations, iot, mock(ActionDraftFacade.class), mock(TaskFacade.class),
                new ObjectMapper(), null);
    }

    private static List<String> deviceIds(Map<String, Object> result) {
        return ((List<?>) result.get("devices")).stream().map(row -> String.valueOf(((Map<?, ?>) row).get("id"))).toList();
    }

    private static Map<String, Object> request(Map<String, Object> arguments) {
        return Map.of("runId", "run-1", "toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolCallId", "call-1", "toolName", "find_device_candidates", "arguments", arguments);
    }
}
