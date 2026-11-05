package ltd.qianc.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Public-seam regressions for the normalized execution result. Legacy persisted
 * results predate status/message; every read path (confirm answer, draft detail,
 * conversation restore) must serve one truthful projection derived from the
 * authoritative terminal status plus the receipts, per-receipt fields and
 * readbacks actually recorded — never from fresh device state, never by
 * re-dispatching a command.
 */
class ActionResultContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);

    // Canonical semantic words of the execution-result contract.
    private static final String DISPATCHED = "已下发";
    private static final String COMPLETED = "已完成";
    private static final String NO_CHANGE = "无需操作";
    private static final String REFUSED = "未受理";
    private static final String PARTIAL = "部分已下发，未全部成功";
    private static final String UNKNOWN = "下发结果未知";
    private static final String UNKNOWN_WITH_REFUSED = "下发结果未知，有未受理项";
    private static final String INTERRUPTED_REASON = "执行中断，已下发指令结果未知，请先查询设备状态";

    private static final AuthorizedDevice LIGHT = new AuthorizedDevice("light-1", "lighting", "灯", "26楼", false);
    private static final AuthorizedDevice LIGHT_B = new AuthorizedDevice("light-2", "lighting", "灯2", "26楼", false);
    private static final AuthorizedDevice AC_UNIT = new AuthorizedDevice("ac-1", "ac", "空调", "26楼", false);
    private static final AuthorizedDevice DOOR = new AuthorizedDevice("door-1", "access", "人脸门禁", "26楼", false);

    @Test
    void legacyStoredResultGainsTruthfulStatusAndMessageAtReadTimeWithoutRewritingEvidence() throws Exception {
        // Real persisted door shape: gateway evidence and receipts exist, status/message do not.
        ObjectNode response = MAPPER.createObjectNode().put("code", 0);
        ObjectNode stored = MAPPER.createObjectNode()
                .put("gateway", "customer")
                .put("outcome", "ACCEPTED")
                .<ObjectNode>set("receipts", array(receipt("door-open", "ACCEPTED").set("response", response)))
                .set("readbacks", array());
        ActionDraftFact draft = terminalDraft("door-legacy", "ACCEPTED", stored, doorCommand());
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new RecordingIotAdapter(Map.of()), new RecordingGateway());

        Map<String, Object> result = result(facade.view(draft));

        assertThat(result.get("status")).isEqualTo("ACCEPTED");
        assertThat(result.get("message")).isEqualTo(DISPATCHED);
        assertThat(result.get("message")).isNotEqualTo(COMPLETED);
        assertThat(result.get("gateway")).isEqualTo("customer");
        assertThat(receipts(result)).singleElement()
                .satisfies(receipt -> assertThat(receipt)
                        .containsEntry("actionId", "door-open")
                        .containsEntry("outcome", "ACCEPTED"));
    }

    @Test
    void executionResultIsTheFlatConfirmAnswerNotADraftWrapper() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.next = dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                array(receipt("light-on", "ACCEPTED")),
                array(readback("light-on", "unconfirmed", Map.of("power", "off"))));
        ActionDraftFacade facade = facade(
                new InMemoryActionDraftStore(pendingDraft("flat-confirm", lightingCommand())),
                new RecordingIotAdapter(Map.of("light-1", false)), gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "flat-confirm", null);
        Map<String, Object> body = facade.executionResult(done);

        assertThat(body.get("draftId")).isEqualTo("flat-confirm");
        assertThat(body.get("status")).isEqualTo("ACCEPTED");
        assertThat(body.get("message")).isEqualTo(DISPATCHED);
        assertThat(body).doesNotContainKeys("actions", "selectedActionIds", "inventory", "counts", "result");
        assertThat(receipts(body)).singleElement()
                .satisfies(receipt -> assertThat(receipt).containsEntry("outcome", "ACCEPTED"));
    }

    @Test
    void acceptedAcknowledgementAloneMeansDispatchedForEveryControlKind() throws Exception {
        assertProjectedMessage(Map.of("light-1", false),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("light-on", "ACCEPTED")), array(readback("light-on", "unconfirmed", Map.of()))),
                "ACCEPTED", DISPATCHED, lightingCommand());
        assertProjectedMessage(Map.of("ac-1", Map.of("power", "off", "temperature", 26)),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("ac-set", "ACCEPTED")), array(readback("ac-set", "unconfirmed", Map.of()))),
                "ACCEPTED", DISPATCHED, acCommand());
        assertProjectedMessage(Map.of(),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("door-open", "ACCEPTED")), array()),
                "ACCEPTED", DISPATCHED, doorCommand());
    }

    @Test
    void doorConfirmSkipsPreStateQueryButKeepsFreshAuthorizationForTheDispatch() throws Exception {
        RecordingIotAdapter iot = new RecordingIotAdapter(Map.of());
        RecordingGateway gateway = new RecordingGateway();
        gateway.next = dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                array(receipt("door-open", "ACCEPTED")), array());
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(pendingDraft("door-only", doorCommand())),
                iot, gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "door-only", null);

        assertThat(done.status()).isEqualTo("ACCEPTED");
        assertThat(iot.stateReads).as("开门不得预先轮询门禁状态").isEmpty();
        assertThat(gateway.dispatches).as("授权校验后仍必须下发门禁指令").hasSize(1);
        assertThat(gateway.dispatches.get(0)).contains("door-open");
    }

    @Test
    void mixedSelectionPreStateQuerySkipsAccessWhileBothAreStillDispatched() throws Exception {
        RecordingIotAdapter iot = new RecordingIotAdapter(Map.of("light-1", false));
        RecordingGateway gateway = new RecordingGateway();
        gateway.next = dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                array(receipt("door-open", "ACCEPTED"), receipt("light-on", "ACCEPTED")), array());
        ActionDraftFacade facade = facade(
                new InMemoryActionDraftStore(pendingDraft("mixed", doorCommand(), lightingCommand())), iot, gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "mixed", null);

        assertThat(done.status()).isEqualTo("ACCEPTED");
        assertThat(iot.stateReads).containsExactly(List.of("light-1"));
        assertThat(gateway.dispatches).hasSize(1);
        assertThat(gateway.dispatches.get(0)).contains("door-open").contains("light-on");
        assertThat(result(facade.view(done)).get("message")).isEqualTo(DISPATCHED);
    }

    @Test
    void executionTimeConfirmationCompletesOnlyNonAccessKinds() throws Exception {
        assertProjectedMessage(Map.of("light-1", false),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("light-on", "ACCEPTED")),
                        array(readback("light-on", "confirmed", Map.of("power", "on")))),
                "ACCEPTED", COMPLETED, lightingCommand());
        // Even stale "confirmed" door evidence must not claim physical completion.
        assertProjectedMessage(Map.of(),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("door-open", "ACCEPTED")),
                        array(readback("door-open", "confirmed", Map.of("openOrClose", 1)))),
                "ACCEPTED", DISPATCHED, doorCommand());
    }

    @Test
    void confirmedReadbackRetainsObservedStateEvidence() throws Exception {
        Map<String, Object> result = confirmAndProject(Map.of("light-1", false),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("light-on", "ACCEPTED")),
                        array(readback("light-on", "confirmed", Map.of("power", "on")))),
                lightingCommand());

        assertThat(result.get("readback")).isEqualTo(Map.of("light-on", "confirmed"));
        assertThat(result.get("observedStates")).isEqualTo(Map.of("light-1", Map.of("power", "on")));
        assertThat(result.get("observedAt")).isEqualTo("2026-10-30T09:00:05Z");
    }

    @Test
    void alreadySatisfiedSelectionReportsNoChangeWithoutAnyDispatch() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        ActionDraftFacade facade = facade(
                new InMemoryActionDraftStore(pendingDraft("satisfied", lightingCommand())),
                new RecordingIotAdapter(Map.of("light-1", true)), gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "satisfied", null);

        assertThat(done.status()).isEqualTo("NO_CHANGE");
        assertThat(gateway.dispatches).isEmpty();
        Map<String, Object> result = result(facade.view(done));
        assertThat(result.get("message")).isEqualTo(NO_CHANGE);
        assertThat(result.get("status")).isEqualTo("NO_CHANGE");
    }

    @Test
    void partialAcceptanceIsNeitherSuccessNorFailureAtReceiptAndFieldLevel() throws Exception {
        assertProjectedMessage(Map.of("light-1", false, "light-2", false),
                dispatch(ActionDraftStatusEnum.PARTIALLY_ACCEPTED, ActionReceiptOutcomeEnum.UNKNOWN,
                        array(receipt("light-a", "ACCEPTED"), receipt("light-b", "REJECTED")), array()),
                "PARTIALLY_ACCEPTED", PARTIAL,
                lightingCommand("light-a", "light-1"), lightingCommand("light-b", "light-2"));

        // Real legacy shape: the old gateway wrote outer REJECTED for any mixed AC
        // response, hiding accepted parameters in nested lowercase response.fields.
        ObjectNode legacyFields = receipt("ac-set", "REJECTED");
        legacyFields.set("response", MAPPER.createObjectNode().set("fields", array(
                MAPPER.createObjectNode().put("field", "power").put("outcome", "accepted"),
                MAPPER.createObjectNode().put("field", "temperature").put("outcome", "rejected"))));
        Map<String, Object> legacy = confirmAndProject(Map.of("ac-1", Map.of("power", "off")),
                dispatch(ActionDraftStatusEnum.REJECTED, ActionReceiptOutcomeEnum.REJECTED,
                        array(legacyFields), array()),
                acCommand());
        assertThat(legacy.get("message")).isEqualTo(PARTIAL);
        assertThat(legacy.get("message")).isNotEqualTo(REFUSED);
        assertThat(legacy.get("status")).isEqualTo("REJECTED");
        assertThat(receipts(legacy)).singleElement().satisfies(projected -> {
            assertThat(projected).containsEntry("outcome", "PARTIALLY_ACCEPTED");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) projected.get("fields");
            assertThat(fields).extracting(field -> field.get("outcome"))
                    .containsExactly("ACCEPTED", "REJECTED");
            // The raw gateway evidence stays exactly as recorded.
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) projected.get("response");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawFields = (List<Map<String, Object>>) response.get("fields");
            assertThat(rawFields).extracting(field -> field.get("outcome"))
                    .containsExactly("accepted", "rejected");
        });

        // Forward shape: top-level uppercase fields and a PARTIALLY_ACCEPTED receipt outcome.
        ObjectNode partialReceipt = receipt("ac-set", "PARTIALLY_ACCEPTED");
        partialReceipt.set("fields", array(
                MAPPER.createObjectNode().put("field", "power").put("outcome", "ACCEPTED"),
                MAPPER.createObjectNode().put("field", "temperature").put("outcome", "REJECTED")));
        Map<String, Object> forward = confirmAndProject(Map.of("ac-1", Map.of("power", "off")),
                dispatch(ActionDraftStatusEnum.PARTIALLY_ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(partialReceipt), array()),
                acCommand());
        assertThat(forward.get("message")).isEqualTo(PARTIAL);
        assertThat(forward.get("message")).isNotEqualTo(DISPATCHED).isNotEqualTo(COMPLETED).isNotEqualTo(REFUSED);
    }

    @Test
    void acceptedFieldEvidenceIsNotDiscardedByALegacyRefusedOuterOutcome() throws Exception {
        // Every field accepted under a coarse outer REJECTED: dispatch truth wins.
        Map<String, Object> allAccepted = confirmAndProject(Map.of("ac-1", Map.of("power", "off")),
                dispatch(ActionDraftStatusEnum.REJECTED, ActionReceiptOutcomeEnum.REJECTED,
                        array(receipt("ac-set", "REJECTED").set("response", MAPPER.createObjectNode().set("fields",
                                array(MAPPER.createObjectNode().put("field", "power").put("outcome", "accepted"),
                                        MAPPER.createObjectNode().put("field", "temperature").put("outcome", "accepted"))))),
                        array()),
                acCommand());
        assertThat(allAccepted.get("message")).isEqualTo(DISPATCHED);
        assertThat(allAccepted.get("status")).isEqualTo("REJECTED");
        assertThat(receipts(allAccepted)).singleElement()
                .satisfies(projected -> assertThat(projected).containsEntry("outcome", "ACCEPTED"));

        // Accepted plus unknown parameter is a partial truth, not a blanket failure.
        Map<String, Object> acceptedWithUnknown = confirmAndProject(Map.of("ac-1", Map.of("power", "off")),
                dispatch(ActionDraftStatusEnum.REJECTED, ActionReceiptOutcomeEnum.REJECTED,
                        array(receipt("ac-set", "REJECTED").set("response", MAPPER.createObjectNode().set("fields",
                                array(MAPPER.createObjectNode().put("field", "power").put("outcome", "accepted"),
                                        MAPPER.createObjectNode().put("field", "temperature").put("outcome", "unknown"))))),
                        array()),
                acCommand());
        assertThat(acceptedWithUnknown.get("message")).isEqualTo(PARTIAL);

        // Refused plus unknown without any accepted parameter never claims a dispatch.
        Map<String, Object> refusedWithUnknown = confirmAndProject(Map.of("ac-1", Map.of("power", "off")),
                dispatch(ActionDraftStatusEnum.REJECTED, ActionReceiptOutcomeEnum.REJECTED,
                        array(receipt("ac-set", "REJECTED").set("response", MAPPER.createObjectNode().set("fields",
                                array(MAPPER.createObjectNode().put("field", "power").put("outcome", "rejected"),
                                        MAPPER.createObjectNode().put("field", "temperature").put("outcome", "unknown"))))),
                        array()),
                acCommand());
        assertThat(refusedWithUnknown.get("message")).isEqualTo(UNKNOWN);
        assertThat(refusedWithUnknown.get("message")).isNotEqualTo(PARTIAL).isNotEqualTo(DISPATCHED);
    }

    @Test
    void explicitRefusalIsNotAcceptedAndItsConfirmAnswerStaysFlat() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.next = dispatch(ActionDraftStatusEnum.REJECTED, ActionReceiptOutcomeEnum.REJECTED,
                array(receipt("light-on", "REJECTED")), array());
        ActionDraftFacade facade = facade(
                new InMemoryActionDraftStore(pendingDraft("refused", lightingCommand())),
                new RecordingIotAdapter(Map.of("light-1", false)), gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "refused", null);

        assertThat(done.status()).isEqualTo("REJECTED");
        Map<String, Object> body = facade.executionResult(done);
        assertThat(body.get("status")).isEqualTo("REJECTED");
        assertThat(body.get("message")).isEqualTo(REFUSED);
        assertThat(body).doesNotContainKeys("actions", "inventory", "result");
    }

    @Test
    void unknownTransportReportsUnknownTruthAndNeverClaimsDispatch() throws Exception {
        Map<String, Object> unknownOnly = confirmAndProject(Map.of("light-1", false),
                dispatch(ActionDraftStatusEnum.ACCEPTED, ActionReceiptOutcomeEnum.ACCEPTED,
                        array(receipt("light-on", "UNKNOWN")), array()),
                lightingCommand());
        assertThat(unknownOnly.get("message")).isEqualTo(UNKNOWN);
        assertThat(unknownOnly.get("message")).isNotEqualTo(DISPATCHED).isNotEqualTo(COMPLETED);

        // Unknown transport is not proof of a dispatch: with no accepted receipt the
        // wording stays unknown even though another action was explicitly refused.
        Map<String, Object> unknownWithRefused = confirmAndProject(Map.of("light-1", false, "light-2", false),
                dispatch(ActionDraftStatusEnum.PARTIALLY_ACCEPTED, ActionReceiptOutcomeEnum.UNKNOWN,
                        array(receipt("light-a", "UNKNOWN"), receipt("light-b", "REJECTED")), array()),
                lightingCommand("light-a", "light-1"), lightingCommand("light-b", "light-2"));
        assertThat(unknownWithRefused.get("message")).isEqualTo(UNKNOWN_WITH_REFUSED);
        assertThat(unknownWithRefused.get("message")).isNotEqualTo(PARTIAL);

        // A known accepted receipt beside an unknown one is a partial truth, not a success.
        Map<String, Object> unknownWithAccepted = confirmAndProject(Map.of("light-1", false, "light-2", false),
                dispatch(ActionDraftStatusEnum.PARTIALLY_ACCEPTED, ActionReceiptOutcomeEnum.UNKNOWN,
                        array(receipt("light-a", "ACCEPTED"), receipt("light-b", "UNKNOWN")), array()),
                lightingCommand("light-a", "light-1"), lightingCommand("light-b", "light-2"));
        assertThat(unknownWithAccepted.get("message")).isEqualTo(PARTIAL);
    }

    @Test
    void interruptedDispatchRetainsUnknownTruthAndRecoveryNeverDispatchesAgain() throws Exception {
        RecordingGateway gateway = new RecordingGateway();
        gateway.failure = new ActionDispatchException("上游控制请求中断", true);
        ActionDraftFacade facade = facade(
                new InMemoryActionDraftStore(pendingDraft("interrupted", lightingCommand())),
                new RecordingIotAdapter(Map.of("light-1", false)), gateway);

        ActionDraftFact done = facade.confirm(PRINCIPAL, "interrupted", null);

        assertThat(done.status()).isEqualTo("INTERRUPTED");
        Map<String, Object> result = result(facade.view(done));
        assertThat(result.get("status")).isEqualTo("INTERRUPTED");
        assertThat(result.get("message")).isEqualTo(UNKNOWN);
        assertThat(String.valueOf(result.get("error"))).contains(INTERRUPTED_REASON);
        assertThat(receipts(result)).singleElement()
                .satisfies(receipt -> assertThat(receipt).containsEntry("outcome", "UNKNOWN"));

        // Read-only recovery and idempotent re-confirm must not re-dispatch the command.
        ActionDraftFact replayed = facade.confirm(PRINCIPAL, "interrupted", null);
        assertThat(replayed.status()).isEqualTo("INTERRUPTED");
        assertThat(gateway.dispatches).hasSize(1);
        assertThat(facade.view(facade.current(PRINCIPAL, "interrupted")).get("result"))
                .isEqualTo(facade.view(done).get("result"));
    }

    @Test
    void nonExecutedDraftsNeverFabricateAnExecutionResult() throws Exception {
        ActionDraftFacade pending = facade(
                new InMemoryActionDraftStore(pendingDraft("still-pending", lightingCommand())),
                new RecordingIotAdapter(Map.of("light-1", false)), new RecordingGateway());
        assertThat(pending.view(pending.current(PRINCIPAL, "still-pending")).get("result")).isNull();
        assertThat(pending.executionResult(pending.current(PRINCIPAL, "still-pending")).get("status"))
                .isEqualTo("PENDING_CONFIRMATION");

        ActionDraftFact cancelled = terminalDraft("cancelled", "CANCELLED", null, lightingCommand());
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(cancelled),
                new RecordingIotAdapter(Map.of()), new RecordingGateway());
        assertThat(facade.view(facade.current(PRINCIPAL, "cancelled")).get("result")).isNull();

        Map<String, Object> body = facade.executionResult(facade.current(PRINCIPAL, "cancelled"));
        assertThat(body.get("draftId")).isEqualTo("cancelled");
        assertThat(body.get("status")).isEqualTo("CANCELLED");
        assertThat(body.get("message")).isEqualTo("草案已取消");
        assertThat(body).doesNotContainKeys("receipts", "actions", "readback", "observedStates");
    }

    @Test
    void legacyRuntimeFlatShapePreservesRecordedMapsAndStillFeedsClassification() throws Exception {
        // Old Runtime confirm shape: no raw readback records, only the recorded maps.
        ObjectNode stored = MAPPER.createObjectNode()
                .put("gateway", "customer")
                .put("outcome", "ACCEPTED")
                .<ObjectNode>set("receipts", array(receipt("light-on", "ACCEPTED")))
                .<ObjectNode>set("readback", MAPPER.createObjectNode().put("light-on", "confirmed"))
                .<ObjectNode>set("observedStates", MAPPER.createObjectNode()
                        .set("light-1", MAPPER.createObjectNode().put("power", "on")))
                .put("observedAt", "2026-10-30T09:00:05Z");
        ActionDraftFact draft = terminalDraft("flat-legacy", "ACCEPTED", stored, lightingCommand());
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new RecordingIotAdapter(Map.of()), new RecordingGateway());

        Map<String, Object> result = result(facade.view(draft));

        assertThat(result.get("message")).isEqualTo(COMPLETED);
        assertThat(result.get("readback")).isEqualTo(Map.of("light-on", "confirmed"));
        assertThat(result.get("observedStates")).isEqualTo(Map.of("light-1", Map.of("power", "on")));
        assertThat(result.get("observedAt")).isEqualTo("2026-10-30T09:00:05Z");
    }

    @Test
    void staleStoredExecutingResultIsReprojectedFromTheAuthoritativeTerminalStatus() throws Exception {
        // A crash or older writer could leave an "EXECUTING" result with a
        // running message on an already terminal draft; the authoritative draft
        // status must win and the accepted evidence must read as dispatched.
        ObjectNode stored = MAPPER.createObjectNode()
                .put("status", "EXECUTING")
                .put("message", "正在执行，请稍候")
                .put("outcome", "UNKNOWN")
                .<ObjectNode>set("receipts", array(receipt("door-open", "ACCEPTED")))
                .set("readbacks", array());
        ActionDraftFact draft = terminalDraft("stale-executing", "ACCEPTED", stored, doorCommand());
        RecordingGateway gateway = new RecordingGateway();
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new RecordingIotAdapter(Map.of()), gateway);

        Map<String, Object> result = result(facade.view(draft));

        assertThat(result.get("status")).isEqualTo("ACCEPTED");
        assertThat(result.get("message")).isEqualTo(DISPATCHED);
        assertThat(result.get("outcome")).isEqualTo("ACCEPTED");

        // Terminal drafts are never re-delivered to the gateway.
        ActionDraftFact replayed = facade.confirm(PRINCIPAL, "stale-executing", null);
        assertThat(replayed.status()).isEqualTo("ACCEPTED");
        assertThat(gateway.dispatches).isEmpty();
    }

    @Test
    void legacyTerminalWithoutDetailedReceiptsStillCannotClaimAcceptedOnARefusal() throws Exception {
        ActionDraftFact draft = terminalDraft("legacy-refused", "REJECTED", MAPPER.createObjectNode(), doorCommand());
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new RecordingIotAdapter(Map.of()), new RecordingGateway());

        Map<String, Object> result = facade.executionResult(draft);

        assertThat(result.get("status")).isEqualTo("REJECTED");
        assertThat(result.get("outcome")).isEqualTo("REJECTED");
        assertThat(result.get("message").toString()).doesNotContain("已下发", "执行中");
    }

    @Test
    void parameterNoOpAndPhysicalEvidenceRetainTheirMeaningAtDeviceLevel() throws Exception {
        String command = action("ac-power", "ac-1", "ac", Map.of("power", "on"));
        for (String outcome : List.of("ALREADY_SATISFIED", "DEVICE_APPLIED")) {
            ObjectNode receipt = receipt("ac-power", "ACCEPTED");
            receipt.putArray("fields").addObject().put("field", "power").put("outcome", outcome);
            ActionDraftFact draft = terminalDraft("fields-" + outcome, "ACCEPTED",
                    MAPPER.createObjectNode().set("receipts", array(receipt)), command);
            ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                    new RecordingIotAdapter(Map.of()), new RecordingGateway());

            Map<String, Object> result = facade.executionResult(draft);

            assertThat(result.get("outcome")).isEqualTo(outcome);
            assertThat(result.get("message")).isEqualTo(outcome.equals("ALREADY_SATISFIED") ? "无需操作" : "已完成");
            assertThat(receipts(result)).singleElement().satisfies(row -> assertThat(row.get("outcome")).isEqualTo(outcome));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static String lightingCommand() {
        return lightingCommand("light-on", "light-1");
    }

    private static String lightingCommand(String actionId, String deviceId) {
        return action(actionId, deviceId, "lighting", Map.of("closeOrOpen", 1));
    }

    private static String acCommand() {
        return action("ac-set", "ac-1", "ac", Map.of("power", "on", "temperature", 24));
    }

    private static String doorCommand() {
        return action("door-open", "door-1", "access", Map.of("controlType", 2));
    }

    private static String action(String id, String deviceId, String kind, Map<String, Object> command) {
        return MAPPER.createObjectNode()
                .put("id", id)
                .put("deviceId", deviceId)
                .put("kind", kind)
                .<ObjectNode>set("command", MAPPER.valueToTree(command))
                .toString();
    }

    private static ActionDraftFact pendingDraft(String id, String... actions) throws Exception {
        return draft(id, 1, "PENDING_CONFIRMATION", null, actions);
    }

    private static ActionDraftFact terminalDraft(String id, String status, ObjectNode result, String... actions)
            throws Exception {
        return draft(id, 2, status, result, actions);
    }

    private static ActionDraftFact draft(String id, long revision, String status, ObjectNode result,
                                         String... actions) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("tenantCode", PRINCIPAL.tenantCode());
        payload.put("ownerKey", PRINCIPAL.ownerKey());
        payload.put("source", "office");
        payload.put("conversationId", "conversation-1");
        payload.put("turnId", "turn-1");
        payload.put("expiresAt", CLOCK.instant().plus(Duration.ofMinutes(10)).toString());
        payload.put("summary", "fixture");
        payload.put("status", status);
        ArrayNode actionList = payload.putArray("actions");
        ArrayNode selected = payload.putArray("selectedActionIds");
        for (String action : actions) {
            JsonNode node = MAPPER.readTree(action);
            actionList.add(node);
            selected.add(node.path("id").asText());
        }
        if (result != null) payload.set("result", result);
        return new ActionDraftFact(id, revision, status, MAPPER.writeValueAsString(payload), "fixture:" + id);
    }

    private static ActionDraftFacade facade(ActionDraftStore drafts, IotAdapter iot, ActionCommandGateway gateway) {
        return new ActionDraftFacade(MAPPER, drafts, iot, null, gateway, null, null, null, null,
                org.mockito.Mockito.mock(ReadSnapshots.class), CLOCK, Duration.ofSeconds(2),
                Duration.ofMinutes(10), null);
    }

    private static void assertProjectedMessage(Map<String, Object> states, ActionDispatchResult gatewayResult,
                                               String expectedStatus, String expectedMessage,
                                               String... actions) throws Exception {
        Map<String, Object> result = confirmAndProject(states, gatewayResult, actions);
        assertThat(result.get("status")).isEqualTo(expectedStatus);
        assertThat(result.get("message")).isEqualTo(expectedMessage);
    }

    private static Map<String, Object> confirmAndProject(Map<String, Object> states,
                                                         ActionDispatchResult gatewayResult,
                                                         String... actions) throws Exception {
        String id = "draft-" + MAPPER.readTree(actions[0]).path("id").asText();
        RecordingGateway gateway = new RecordingGateway();
        gateway.next = gatewayResult;
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(pendingDraft(id, actions)),
                new RecordingIotAdapter(states), gateway);
        ActionDraftFact done = facade.confirm(PRINCIPAL, id, null);
        return result(facade.view(done));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> view) {
        Map<String, Object> result = (Map<String, Object>) view.get("result");
        assertThat(result).as("executed draft must expose a result").isNotNull();
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> receipts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("receipts");
    }

    private static ObjectNode receipt(String actionId, String outcome) {
        return MAPPER.createObjectNode().put("actionId", actionId).put("outcome", outcome);
    }

    private static ObjectNode readback(String actionId, String outcome, Map<String, Object> observedState) {
        return MAPPER.createObjectNode()
                .put("actionId", actionId)
                .put("outcome", outcome)
                .<ObjectNode>set("observedState", MAPPER.valueToTree(observedState))
                .put("observedAt", "2026-10-30T09:00:05Z");
    }

    private static ArrayNode array(JsonNode... nodes) {
        ArrayNode array = MAPPER.createArrayNode();
        for (JsonNode node : nodes) array.add(node);
        return array;
    }

    private static ActionDispatchResult dispatch(ActionDraftStatusEnum status, ActionReceiptOutcomeEnum outcome,
                                                 ArrayNode receipts, ArrayNode readbacks) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("gateway", "customer");
        result.set("receipts", receipts);
        result.set("readbacks", readbacks);
        return new ActionDispatchResult(status, result.toString(), true, outcome);
    }

    /** Public-seam fixture: records every state read instead of inferring from commands. */
    private static final class RecordingIotAdapter implements IotAdapter {
        private final Map<String, Object> states;
        final List<List<String>> stateReads = new ArrayList<>();

        RecordingIotAdapter(Map<String, Object> states) {
            this.states = states;
        }

        @Override
        public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(),
                    List.of(LIGHT, LIGHT_B, AC_UNIT, DOOR)));
        }

        @Override
        public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
            stateReads.add(List.copyOf(deviceIds));
            Map<String, Object> selected = new LinkedHashMap<>();
            for (String id : deviceIds) selected.put(id, states.get(id));
            return Mono.just(new DeviceStateReadResult(selected, CLOCK.instant().toString()));
        }
    }

    private static final class RecordingGateway implements ActionCommandGateway {
        final List<String> dispatches = new ArrayList<>();
        private ActionDispatchResult next;
        private RuntimeException failure;

        @Override
        public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                              String selectedActionsJson) {
            return dispatch(identity, scope, selectedActionsJson, Map.of());
        }

        @Override
        public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                              String selectedActionsJson, Map<String, Object> observedStates) {
            dispatches.add(selectedActionsJson);
            if (failure != null) throw failure;
            return next;
        }
    }
}
