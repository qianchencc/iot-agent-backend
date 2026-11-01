package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskProposalNormalizationContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void expandsAcWindowCheckpointsAndDerivesCondition() throws Exception {
        TaskProposalFact saved = propose(acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z"));

        JsonNode payload = mapper.readTree(saved.payloadJson());
        assertThat(payload.path("checkpointCount").asInt()).isEqualTo(4);
        assertThat(payload.path("phases").size()).isEqualTo(4);
        assertThat(payload.path("phases").get(0).path("at").asText()).isEqualTo("2026-11-02T06:30:00Z");
        JsonNode ac = payload.path("phases").get(0).path("read").path("ac");
        assertThat(ac.path("condition").path("power").asText()).isEqualTo("on");
        assertThat(ac.path("condition").path("mode").asText()).isEqualTo("cool");
        assertThat(ac.path("condition").path("setpoint").path("value").asInt()).isEqualTo(26);
        assertThat(ac.path("action").path("setpoint").asInt()).isEqualTo(26);
        assertThat(payload.path("phases").get(0).path("read").path("devices").get(0).path("kind").asText())
                .isEqualTo("ac");
    }

    @Test
    void rejectsUnsupportedCadenceAndMissingWindow() {
        assertThatThrownBy(() -> propose(acPayload(20, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("15、30或60");
        assertThatThrownBy(() -> propose("{\"label\":\"巡检\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\","
                + "\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"adjust_to_26\","
                + "\"target\":26,\"cadenceMinutes\":30}}}]}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("监测窗口");
    }

    @Test
    void rejectsAcConditionOnNonAcDevice() {
        assertThatThrownBy(() -> propose(acPayloadFor("light-1", 30)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空调");
    }

    private TaskProposalFact propose(String payload) {
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TaskFacade facade = new TaskFacade(mapper, mock(TaskFactStore.class), proposals, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return facade.propose(PRINCIPAL, "office", "conversation", "turn", payload, "idem");
    }

    private String acPayload(int cadence, String from, String to) {
        return acPayloadFor("ac-1", cadence, from, to);
    }

    private String acPayloadFor(String deviceId, int cadence) {
        return acPayloadFor(deviceId, cadence, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z");
    }

    private String acPayloadFor(String deviceId, int cadence, String from, String to) {
        return "{\"label\":\"空调巡检\",\"interval\":{\"startsAt\":\"" + from + "\",\"endsAt\":\"" + to + "\"},"
                + "\"phases\":[{\"at\":\"" + from + "\",\"actions\":[],\"read\":{\"deviceIds\":[\"" + deviceId
                + "\"],\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26,\"cadenceMinutes\":" + cadence + "}}}]}";
    }

    private IotAdapter adapter() {
        AuthorizedDevice ac = new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                "control-ac-1", "office-ac-1", "hub-1", "area-1", List.of(List.of("26楼", "会议室")),
                List.of("region-1"), Map.of("sourceKeys", List.of("microbrain.ac")));
        AuthorizedDevice light = new AuthorizedDevice("light-1", "lighting", "灯", "26楼", false,
                "control-light-1", "office-light-1", "hub-1", "area-1", List.of(List.of("26楼")),
                List.of("region-1"), Map.of("sourceKeys", List.of("microbrain.lighting")));
        AuthorizedDeviceScope scope = new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() {
                return new RequestIdentity("Bearer fixture", "LOCAL", IotSource.OFFICE);
            }
            @Override public IotSource source() { return IotSource.OFFICE; }
            @Override public List<AuthorizedDevice> devices() { return List.of(ac, light); }
        };
        return new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(scope);
            }
        };
    }
}
