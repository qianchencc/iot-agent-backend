package ltd.qianc.iotagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDispatchException;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.action.ActionDraftFact;
import ltd.qianc.iotagent.action.ActionDraftStatusEnum;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.action.ActionReceiptOutcomeEnum;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ImmediateActionHistoryContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private final AtomicInteger dispatches = new AtomicInteger();

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:immediate_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE task_facts(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(64) UNIQUE,version BIGINT,status VARCHAR(32),payload_json VARCHAR(20000),idempotency_key VARCHAR(128) UNIQUE,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE task_phases(id BIGINT AUTO_INCREMENT PRIMARY KEY,phase_id VARCHAR(64) UNIQUE,task_id VARCHAR(64),source VARCHAR(16),version BIGINT,status VARCHAR(32),due_at TIMESTAMP,payload_json VARCHAR(20000),definition_json VARCHAR(20000),result_json VARCHAR(20000),claimed_at TIMESTAMP,ended_at TIMESTAMP,failure_reason VARCHAR(1024),create_time TIMESTAMP,update_time TIMESTAMP)");
    }

    @Test
    void acceptedWithoutReadbackRemainsUnconfirmedAndRepeatedConfirmNeverDuplicatesHistoryOrDispatch() throws Exception {
        ActionDraftFacade facade = facade(false, false);
        ActionDraftFact proposed = proposal(facade);
        ActionDraftFact completed = facade.confirm(PRINCIPAL, proposed.draftId(), proposed.revision());
        facade.confirm(PRINCIPAL, proposed.draftId(), null);
        assertThat(completed.status()).isEqualTo("ACCEPTED");
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_facts", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts", String.class)).isEqualTo("COMPLETED");
        JsonNode task = MAPPER.readTree(jdbc.queryForObject("SELECT payload_json FROM task_facts", String.class));
        assertThat(task.path("sourceType").asText()).isEqualTo("immediate_draft");
        assertThat(task.path("sourceDraftId").asText()).isEqualTo(proposed.draftId());
        assertThat(task.path("creatorName").asText()).isEqualTo("成员");
        JsonNode phase = MAPPER.readTree(jdbc.queryForObject("SELECT result_json FROM task_phases", String.class));
        assertThat(phase.path("devices").path("light-a").path("outcome").asText()).isEqualTo("unconfirmed");
        assertThat(phase.path("devices").path("light-a").path("receipt").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void mixedDeviceReceiptsCreatePartialHistoryInsteadOfClaimingAllSucceeded() throws Exception {
        ActionDraftFacade facade = facade(true, false);
        ActionDraftFact proposed = proposal(facade);
        facade.confirm(PRINCIPAL, proposed.draftId(), proposed.revision());
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts", String.class)).isEqualTo("PARTIAL");
        JsonNode phase = MAPPER.readTree(jdbc.queryForObject("SELECT result_json FROM task_phases", String.class));
        assertThat(phase.path("devices").path("light-b").path("outcome").asText()).isEqualTo("rejected");
    }

    @Test
    void uncertainDispatchCreatesInterruptedHistoryAndCannotBeRetriedByConfirm() {
        ActionDraftFacade facade = facade(false, true);
        ActionDraftFact proposed = proposal(facade);
        assertThat(facade.confirm(PRINCIPAL, proposed.draftId(), proposed.revision()).status()).isEqualTo("INTERRUPTED");
        facade.confirm(PRINCIPAL, proposed.draftId(), null);
        assertThat(dispatches.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM task_facts", String.class)).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM task_phases", String.class)).contains("结果未知");
        try {
            JsonNode phase = MAPPER.readTree(jdbc.queryForObject("SELECT result_json FROM task_phases", String.class));
            assertThat(phase.path("devices").path("light-a").path("outcome").asText()).isEqualTo("unknown");
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
    }

    @Test
    void solePartiallyAcceptedDeviceCreatesPartialHistoryAndPreservesParameterFacts() throws Exception {
        ObjectNode receipt = MAPPER.createObjectNode().put("actionId", "a").put("outcome", "PARTIALLY_ACCEPTED");
        receipt.putArray("fields").addObject().put("field", "power").put("outcome", "ACCEPTED");
        ((ArrayNode) receipt.get("fields")).addObject().put("field", "temperature").put("outcome", "REJECTED");

        JsonNode phase = recordReceipt("ac", receipt, false);

        assertThat(jdbc.queryForObject("SELECT status FROM task_facts", String.class)).isEqualTo("PARTIAL");
        JsonNode device = phase.path("devices").path("device");
        assertThat(device.path("outcome").asText()).isEqualTo("unconfirmed");
        assertThat(device.path("fields").get(0).path("outcome").asText()).isEqualTo("ACCEPTED");
        assertThat(device.path("fields").get(1).path("outcome").asText()).isEqualTo("REJECTED");
    }

    @Test
    void doorHistoryCannotClaimPhysicalVerificationFromARecordedReadback() throws Exception {
        JsonNode phase = recordReceipt("access",
                MAPPER.createObjectNode().put("actionId", "a").put("outcome", "ACCEPTED"), true);

        assertThat(jdbc.queryForObject("SELECT status FROM task_facts", String.class)).isEqualTo("COMPLETED");
        assertThat(phase.path("devices").path("device").path("outcome").asText()).isEqualTo("unconfirmed");
    }

    private JsonNode recordReceipt(String kind, ObjectNode receipt, boolean readbackConfirmed) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("source", "office").put("tenantCode", PRINCIPAL.tenantCode())
                .put("ownerKey", PRINCIPAL.ownerKey()).put("confirmedAt", CLOCK.instant().toString())
                .put("endedAt", CLOCK.instant().toString()).put("conversationId", "history").put("turnId", "turn");
        body.putArray("actions").addObject().put("id", "a").put("deviceId", "device").put("kind", kind);
        body.putArray("selectedActionIds").add("a");
        ObjectNode result = body.putObject("result");
        result.putArray("receipts").add(receipt);
        if (readbackConfirmed) result.putArray("readbacks").addObject().put("actionId", "a").put("outcome", "confirmed");
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        AuthorizedDeviceScope scope = AuthorizedDeviceScope.of(identity, identity.source(),
                List.of(new AuthorizedDevice("device", kind, "设备", "办公室", false)));
        TaskFactStoresFactory.immediateActionHistory(jdbc, transactions, MAPPER).record(
                new ActionDraftFact("history", 2, "PARTIALLY_ACCEPTED".equals(receipt.path("outcome").asText())
                        ? "PARTIALLY_ACCEPTED" : "ACCEPTED", body.toString(), "history"), scope);
        return MAPPER.readTree(jdbc.queryForObject("SELECT result_json FROM task_phases", String.class));
    }

    private ActionDraftFact proposal(ActionDraftFacade facade) {
        return facade.propose(PRINCIPAL, "office", "conversation", "turn",
                "{\"candidateSetIds\":[\"all\"],\"summary\":\"打开办公室照明\",\"actions\":[{\"deviceId\":\"light-a\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":1}},{\"deviceId\":\"light-b\",\"kind\":\"lighting\",\"command\":{\"closeOrOpen\":1}}]}", "immediate");
    }

    private ActionDraftFacade facade(boolean rejectSecond, boolean interrupted) {
        List<AuthorizedDevice> devices = List.of(new AuthorizedDevice("light-a", "lighting", "灯A", "办公室", false),
                new AuthorizedDevice("light-b", "lighting", "灯B", "办公室", false));
        ltd.qianc.iotagent.iot.IotAdapter iot = new ltd.qianc.iotagent.iot.IotAdapter() {
            @Override public reactor.core.publisher.Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return reactor.core.publisher.Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices));
            }
            @Override public reactor.core.publisher.Mono<ltd.qianc.iotagent.iot.DeviceStateReadResult> readDeviceStates(
                    RequestIdentity identity, List<String> ids) {
                return reactor.core.publisher.Mono.just(new ltd.qianc.iotagent.iot.DeviceStateReadResult(
                        Map.of("light-a", false, "light-b", false), CLOCK.instant().toString()));
            }
        };
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        ltd.qianc.iotagent.iot.DeviceCandidateSetStore candidates = mock(ltd.qianc.iotagent.iot.DeviceCandidateSetStore.class);
        org.mockito.Mockito.when(candidates.find("all", "conversation")).thenReturn(java.util.Optional.of(new ltd.qianc.iotagent.iot.DeviceCandidateRecord(
                "all", "conversation", PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), identity.source(),
                AuthorizedDeviceScope.of(identity, identity.source(), devices).catalogFingerprint(),
                new ltd.qianc.iotagent.iot.DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                devices, true, devices.size(), CLOCK.instant().toString(), "exact", null)));
        ActionCommandGateway gateway = new ActionCommandGateway() {
            @Override
            public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selected) {
                dispatches.incrementAndGet();
                if (interrupted) throw new ActionDispatchException("通信中断", true);
                try {
                    ArrayNode receipts = MAPPER.createArrayNode();
                    for (JsonNode action : MAPPER.readTree(selected)) {
                        ObjectNode receipt = receipts.addObject();
                        receipt.put("actionId", action.path("id").asText());
                        receipt.put("outcome", rejectSecond && "light-b".equals(action.path("deviceId").asText()) ? "REJECTED" : "ACCEPTED");
                    }
                    ObjectNode result = MAPPER.createObjectNode();
                    result.set("receipts", receipts);
                    return new ActionDispatchResult(rejectSecond ? ActionDraftStatusEnum.PARTIALLY_ACCEPTED : ActionDraftStatusEnum.ACCEPTED,
                            result.toString(), true, ActionReceiptOutcomeEnum.ACCEPTED);
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
        Map<String, ActionDraftFact> values = new java.util.LinkedHashMap<>();
        ActionDraftStore drafts = new ActionDraftStore() {
            @Override public ActionDraftFact create(ActionDraftFact draft) {
                values.put(draft.draftId(), draft);
                return draft;
            }
            @Override public java.util.Optional<ActionDraftFact> find(String id) {
                return java.util.Optional.ofNullable(values.get(id));
            }
            @Override public ActionDraftFact update(String id, long revision, ActionDraftFact replacement) {
                if (values.get(id).revision() != revision) throw new IllegalStateException("revision changed");
                values.put(id, replacement);
                return replacement;
            }
            @Override public java.util.Optional<ActionDraftFact> findByIdempotencyKey(String key) {
                return values.values().stream().filter(draft -> draft.idempotencyKey().equals(key)).findFirst();
            }
            @Override public List<ActionDraftFact> findByConversation(String conversation) {
                return List.copyOf(values.values());
            }
        };
        return new ActionDraftFacade(MAPPER, drafts, iot, candidates, gateway, null, null, null, null,
                mock(ReadSnapshots.class), CLOCK, Duration.ofSeconds(1), Duration.ofMinutes(10),
                TaskFactStoresFactory.immediateActionHistory(jdbc, transactions, MAPPER));
    }
}
