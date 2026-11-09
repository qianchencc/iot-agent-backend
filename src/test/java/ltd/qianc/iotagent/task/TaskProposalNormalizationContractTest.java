package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskProposalNormalizationContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");
    private final ObjectMapper mapper = new ObjectMapper();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"company", "office"})
    void verifiedCreatorNameOverridesSuggestedNameEvenWhenMetadataReadIsUnavailable(String source) throws Exception {
        PrincipalInfo verified = new PrincipalInfo("Bearer verified-fixture", "LOCAL",
                "fixture-creator", "https://identity.example", "  示例创建者  ", null);
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IotAdapter sourceAware = identity -> adapter().loadAuthorizedDeviceScope(identity)
                .map(scope -> AuthorizedDeviceScope.of(identity, identity.source(), scope.devices()));
        TaskFacade facade = new TaskFacade(mapper, mock(TaskFactStore.class), proposals, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, sourceAware, null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z"));
        request.put("creatorName", "未经核验的建议姓名").put("principalId", "suggested-person");

        JsonNode saved = mapper.readTree(facade.propose(verified, source, "conversation", "turn",
                request.toString(), "creator-name").payloadJson());

        assertThat(saved.path("creatorName").asText()).isEqualTo("示例创建者");
        assertThat(saved.path("principalId").asText()).isEqualTo("fixture-creator");
        assertThat(saved.path("identityRealm").asText()).isEqualTo("https://identity.example");
    }

    @Test
    void relativeTaskNamesUseTheScheduledShanghaiDateWithoutMovingExecution() throws Exception {
        String base = acPayload(30, "2026-11-02T17:00:00Z", "2026-11-02T18:00:00Z");
        JsonNode original = mapper.readTree(propose(base).payloadJson());
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(base);
        request.put("title", "明天空调调温检查");
        request.put("label", "明天1–2点26楼办公区空调调温检查");

        JsonNode saved = mapper.readTree(propose(request.toString()).payloadJson());

        assertThat(saved.path("title").asText()).isEqualTo("2026-11-03 空调调温检查");
        assertThat(saved.path("label").asText()).isEqualTo("2026-11-03 1–2点26楼办公区空调调温检查");
        assertThat(saved.path("phases")).isEqualTo(original.path("phases"));
        assertThat(saved.path("interval")).isEqualTo(original.path("interval"));
        assertThat(mapper.readTree(propose(saved.toString()).payloadJson()).path("title"))
                .isEqualTo(saved.path("title"));
    }

    @Test
    void recurringTaskNamesDoNotKeepRelativeDaysOrFreezeTheFirstOccurrenceDate() throws Exception {
        var request = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z"));
        request.put("label", "明天空调巡检");
        request.set("repeat", mapper.createObjectNode().put("frequency", "daily"));

        JsonNode saved = mapper.readTree(propose(request.toString()).payloadJson());

        assertThat(saved.path("title").asText()).isEqualTo("空调巡检");
        assertThat(saved.path("label").asText()).isEqualTo("空调巡检");
        assertThat(saved.path("repeat").path("frequency").asText()).isEqualTo("daily");
        assertThat(saved.path("interval").path("startsAt").asText()).isEqualTo("2026-11-02T06:00:00Z");
    }

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

    @Test
    void rejectsOverflowFractionalAndTextualControlValuesInsteadOfTruncating() {
        for (String payload : List.of(
                "{\"label\":\"照明\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"light-1\",\"closeOrOpen\":4294967297}]}]}",
                "{\"label\":\"照明\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"light-1\",\"closeOrOpen\":1.9}]}]}",
                "{\"label\":\"照明\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"light-1\",\"closeOrOpen\":\"1\"}]}]}",
                "{\"label\":\"门禁\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"door-1\",\"controlType\":4294967298}]}]}",
                "{\"label\":\"门禁\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"door-1\",\"controlType\":2.5}]}]}",
                "{\"label\":\"空调\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"ac-1\",\"command\":{\"temperature\":4294967322}}]}]}",
                "{\"label\":\"空调\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":"
                        + "[{\"deviceId\":\"ac-1\",\"command\":{\"temperature\":26.5}}]}]}",
                "{\"label\":\"巡检\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[],"
                        + "\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\","
                        + "\"target\":4294967322}}}]}",
                "{\"label\":\"巡检\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[],"
                        + "\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\","
                        + "\"target\":null}}}]}")) {
            assertThatThrownBy(() -> propose(payload))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code())
                            .isEqualTo("INVALID_TASK_PAYLOAD"));
        }
    }

    @Test
    void rejectsPhaseMixingControlActionsWithReadOnlyCheck() {
        assertThatThrownBy(() -> propose("{\"label\":\"混合阶段\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\","
                + "\"actions\":[{\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":1}}],"
                + "\"read\":{\"devices\":[{\"id\":\"ac-1\",\"kind\":\"ac\"}]}}]}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("混用");
    }

    @Test
    void rejectsRootLevelControlMixedWithReadOnlyCheck() {
        assertThatThrownBy(() -> propose("{\"label\":\"根级混合\",\"at\":\"2026-11-02T06:00:00Z\","
                + "\"actions\":[{\"deviceId\":\"light-1\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":1}}],"
                + "\"read\":{\"devices\":[{\"id\":\"ac-1\",\"kind\":\"ac\"}]}}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("混用");
    }

    @Test
    void acceptsReadOnlyPhaseWithEmptyActions() throws Exception {
        TaskProposalFact saved = propose("{\"label\":\"只读\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\","
                + "\"actions\":[],\"read\":{\"devices\":[{\"id\":\"ac-1\",\"kind\":\"ac\"}]}}]}");
        JsonNode first = mapper.readTree(saved.payloadJson()).path("phases").get(0);
        assertThat(first.path("actions").size()).isZero();
        assertThat(first.path("read").path("devices").get(0).path("id").asText()).isEqualTo("ac-1");
    }

    @Test
    void windowExpansionIncludesTheEndBoundaryAndTruncatesToTheCadenceGrid() throws Exception {
        JsonNode onGrid = mapper.readTree(propose(acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z")).payloadJson());
        assertThat(onGrid.path("phases")).hasSize(4);
        assertThat(onGrid.path("phases").get(0).path("at").asText()).isEqualTo("2026-11-02T06:30:00Z");
        assertThat(onGrid.path("phases").get(3).path("at").asText()).isEqualTo("2026-11-02T08:00:00Z");

        JsonNode offGrid = mapper.readTree(propose(acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T07:10:00Z")).payloadJson());
        assertThat(offGrid.path("phases")).hasSize(2);
        assertThat(offGrid.path("phases").get(1).path("at").asText()).isEqualTo("2026-11-02T07:00:00Z");
    }

    @Test
    void windowWithoutCadenceClarifiesAndSavesNoProposal() {
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        assertThatThrownBy(() -> propose("{\"label\":\"巡检\",\"interval\":{\"startsAt\":\"2026-11-02T06:00:00Z\","
                + "\"endsAt\":\"2026-11-02T08:00:00Z\"},\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[],"
                + "\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26}}}]}", proposals))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("AC_CADENCE_REQUIRED");
        verify(proposals, never()).save(any());
    }

    @Test
    void singleCheckWithoutWindowStaysConditional() throws Exception {
        JsonNode payload = mapper.readTree(propose("{\"label\":\"单次检查\",\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\","
                + "\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}")
                .payloadJson());
        assertThat(payload.path("taskKind").asText()).isEqualTo("conditional");
        assertThat(payload.path("checkpointCount").asInt()).isEqualTo(1);
        assertThat(payload.path("phases").get(0).path("read").path("ac").path("mode").asText()).isEqualTo("inspect");
    }

    @Test
    void handWrittenAcCheckpointListsAreRejectedInFavorOfServerWindows() {
        assertThatThrownBy(() -> propose("{\"label\":\"手写检查点\",\"phases\":["
                + "{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}},"
                + "{\"at\":\"2026-11-02T06:30:00Z\",\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只检查一次");
        assertThatThrownBy(() -> propose("{\"label\":\"检查加控制\",\"phases\":["
                + "{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[{\"deviceId\":\"light-1\",\"closeOrOpen\":1}]},"
                + "{\"at\":\"2026-11-02T06:30:00Z\",\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只检查一次");
    }

    @Test
    void recurringAcCheckpointsRequireWindowAndCadence() {
        assertThatThrownBy(() -> propose("{\"label\":\"每天单次检查\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"phases\":[{\"at\":\"2026-11-02T06:00:00Z\",\"actions\":[],"
                + "\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("AC_WINDOW_REQUIRED");
    }

    @Test
    void activeWindowKeepsOnlyRemainingFutureCheckpoints() throws Exception {
        JsonNode payload = mapper.readTree(propose(acPayload(30, "2026-11-02T04:00:00Z", "2026-11-02T06:30:00Z")).payloadJson());
        assertThat(payload.path("taskKind").asText()).isEqualTo("conditional");
        assertThat(payload.path("checkpointCount").asInt()).isEqualTo(3);
        assertThat(payload.path("phases").get(0).path("at").asText()).isEqualTo("2026-11-02T05:30:00Z");
        assertThat(payload.path("phases").get(2).path("at").asText()).isEqualTo("2026-11-02T06:30:00Z");

        assertThatThrownBy(() -> propose(acPayload(30, "2026-11-02T03:00:00Z", "2026-11-02T04:30:00Z")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TASK_TIME_PASSED");
    }

    @Test
    void recurringActiveWindowKeepsTheFullTemplateForFollowingDays() throws Exception {
        JsonNode payload = mapper.readTree(propose("{\"label\":\"每日窗口巡检\",\"repeat\":{\"frequency\":\"daily\"},"
                + "\"interval\":{\"startsAt\":\"2026-11-02T04:00:00Z\",\"endsAt\":\"2026-11-02T06:30:00Z\"},"
                + "\"phases\":[{\"at\":\"2026-11-02T04:00:00Z\",\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],"
                + "\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26,\"cadenceMinutes\":30}}}]}").payloadJson());
        assertThat(payload.path("taskKind").asText()).isEqualTo("recurring");
        assertThat(payload.path("phases")).hasSize(5);
        assertThat(payload.path("phases").get(0).path("at").asText()).isEqualTo("2026-11-02T04:30:00Z");
        assertThat(payload.path("phases").get(4).path("at").asText()).isEqualTo("2026-11-02T06:30:00Z");
        assertThat(payload.path("interval").path("startsAt").asText()).isEqualTo("2026-11-02T04:00:00Z");
    }

    @Test
    void justPassedSingleCheckStaysSchedulableWithinTenMinutes() throws Exception {
        assertThat(mapper.readTree(propose("{\"label\":\"刚过检查\",\"phases\":[{\"at\":\"2026-11-02T04:55:00Z\","
                + "\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}")
                .payloadJson()).path("taskKind").asText()).isEqualTo("conditional");
        assertThatThrownBy(() -> propose("{\"label\":\"过久检查\",\"phases\":[{\"at\":\"2026-11-02T04:49:00Z\","
                + "\"actions\":[],\"read\":{\"deviceIds\":[\"ac-1\"],\"ac\":{\"mode\":\"inspect\",\"target\":26}}}]}"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TASK_TIME_PASSED");
    }

    @Test
    void expiredFutureProposalRenewsItsTtlWithoutMovingTheOriginalScheduleOrCreatingATask() throws Exception {
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        TaskProposalFact original = propose(acPayload(30, "2026-11-02T06:00:00Z", "2026-11-02T08:00:00Z"), proposals);
        when(proposals.find(original.proposalId())).thenReturn(Optional.of(original));
        java.util.concurrent.atomic.AtomicReference<TaskProposalFact> saved = new java.util.concurrent.atomic.AtomicReference<>();
        when(proposals.updateIfVersion(any(), org.mockito.ArgumentMatchers.eq(original.version())))
                .thenAnswer(invocation -> { saved.set(invocation.getArgument(0)); return Optional.of(saved.get()); });
        TaskFactStore facts = mock(TaskFactStore.class);
        TaskFacade facade = new TaskFacade(mapper, facts, proposals, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter(), null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class),
                Clock.fixed(NOW.plusSeconds(16 * 60), ZoneOffset.UTC));
        assertThatThrownBy(() -> facade.confirmProposal(PRINCIPAL, original.proposalId(), original.version(), "office"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("TASK_RECONFIRM_REQUIRED"));
        assertThat(mapper.readTree(saved.get().payloadJson()).path("phases"))
                .isEqualTo(mapper.readTree(original.payloadJson()).path("phases"));
        assertThat(saved.get().version()).isGreaterThan(original.version());
        org.mockito.Mockito.verifyNoInteractions(facts);
    }

    private TaskProposalFact propose(String payload) {
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        return propose(payload, proposals);
    }

    private TaskProposalFact propose(String payload, TaskProposalStore proposals) {
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TaskFacade facade = new TaskFacade(mapper, mock(TaskFactStore.class), proposals, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter(), null, null,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), Clock.fixed(NOW, ZoneOffset.UTC));
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
                return new RequestIdentity("Bearer fixture", "LOCAL", IotSourceEnum.OFFICE);
            }
            @Override public IotSourceEnum source() { return IotSourceEnum.OFFICE; }
            @Override public List<AuthorizedDevice> devices() { return List.of(ac, light); }
        };
        return new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(scope);
            }
        };
    }
}
