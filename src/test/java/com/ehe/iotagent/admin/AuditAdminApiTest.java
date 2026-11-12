package com.ehe.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.ehe.iotagent.audit.AuditEventFact;
import com.ehe.iotagent.audit.AuditEventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 审计管理端点走既有 Admin 边界：未登录拒绝；成员索引按可信身份三元组分组且不借名；
 * 事件查询走有界过滤与稳定游标；详情只输出白名单投影，隐藏思维、凭据与原始工具响应不外泄；
 * 审计存储故障显式 503，不以空成功掩盖。
 */
@SpringBootTest(properties = {
        "agent.bridge.token=test-bridge",
        "iot.identity.verify=false",
        "agent.video.allowed-origin=",
        "admin.config-file=target/test-admin-config-audit-does-not-exist.properties",
        "admin.password=admin-test-password"
})
@AutoConfigureMockMvc
class AuditAdminApiTest {

    private static final String ORIGIN = "http://127.0.0.1:5185";
    private static final String TENANT = "XSJ";
    private static final String REALM_A = "https://a.example.com/";
    private static final String REALM_B = "https://b.example.com/";

    private static final InMemoryAudit AUDIT = new InMemoryAudit();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void resetAudit() {
        AUDIT.reset();
        seed();
    }

    @Test
    void auditEndpointsRequireAdminSession() throws Exception {
        mockMvc.perform(get("/api/admin/audit/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
        mockMvc.perform(get("/api/admin/audit/members").param("tenantCode", TENANT))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/audit/event-types"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/audit").param("tenantCode", TENANT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void memberIndexSeparatesSamePrincipalIdAcrossRealmsAndKeepsUnresolvedHonest() throws Exception {
        AdminSession session = login();
        MvcResult result = mockMvc.perform(get("/api/admin/audit/members")
                        .param("tenantCode", TENANT).header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(REALM_A).contains(REALM_B)
                .contains("测试用户").contains("13800000000");
        com.fasterxml.jackson.databind.JsonNode members = mapper.readTree(body).path("members");
        assertThat(members).hasSize(2);
        com.fasterxml.jackson.databind.JsonNode memberA = members.get(0);
        com.fasterxml.jackson.databind.JsonNode memberB = members.get(1);
        assertThat(memberA.path("identityRealm").asText()).isEqualTo(REALM_A);
        assertThat(memberA.path("principalId").asText()).isEqualTo("p-1");
        assertThat(memberA.path("eventCount").asLong()).isEqualTo(3);
        assertThat(memberA.path("identityResolved").asBoolean()).isTrue();
        assertThat(memberB.path("identityRealm").asText()).isEqualTo(REALM_B);
        assertThat(memberB.path("principalId").asText()).isEqualTo("p-1");
        assertThat(memberB.path("eventCount").asLong()).isEqualTo(1);
        assertThat(memberB.path("identityResolved").asBoolean()).isTrue();

        mockMvc.perform(get("/api/admin/audit/members")
                        .param("tenantCode", TENANT).param("identityRealm", REALM_B)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.members[0].identityRealm").value(REALM_B))
                .andExpect(jsonPath("$.members[0].eventCount").value(1));

        mockMvc.perform(get("/api/admin/audit/members")
                        .param("tenantCode", "ADMIN").header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.members[0].identityResolved").value(false))
                .andExpect(jsonPath("$.members[0].principalId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.members[0].displayName").value("未识别主体"))
                .andExpect(jsonPath("$.members[0].eventCount").value(1));
    }

    @Test
    void listFiltersByResultSourceWindowAndPagesWithStableCursor() throws Exception {
        AdminSession session = login();

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("limit", "2")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].eventType").value("ACTION_DRAFT_RESOLVED"))
                .andExpect(jsonPath("$.events[1].eventType").value("ACTION_DRAFT_RESOLVED"))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andDo(result -> {
                    String cursor = mapper.readTree(result.getResponse().getContentAsString())
                            .path("nextCursor").asText();
                    String second = mockMvc.perform(get("/api/admin/audit")
                                    .param("tenantCode", TENANT).param("limit", "2").param("cursor", cursor)
                                    .header("Cookie", session.cookie()))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.events.length()").value(2))
                            .andExpect(jsonPath("$.events[0].eventType").value("AGENT_RUN_COMPLETED"))
                            .andExpect(jsonPath("$.events[1].eventType").value("AGENT_REQUESTED"))
                            .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                            .andReturn().getResponse().getContentAsString();
                    String tail = mapper.readTree(second).path("nextCursor").asText();
                    mockMvc.perform(get("/api/admin/audit")
                                    .param("tenantCode", TENANT).param("limit", "2").param("cursor", tail)
                                    .header("Cookie", session.cookie()))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.events.length()").value(0))
                            .andExpect(jsonPath("$.nextCursor").doesNotExist());
                });

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT)
                        .param("identityRealm", REALM_A).param("principalId", "p-1")
                        .param("result", "COMPLETED")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("AGENT_RUN_COMPLETED"))
                .andExpect(jsonPath("$.events[0].result").value("COMPLETED"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT)
                        .param("from", "2026-11-04T01:02:30Z").param("to", "2026-11-04T01:02:40Z")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("from", "not-an-instant")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_INVALID"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("limit", "0")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_INVALID"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", "ADMIN").param("unresolved", "true")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("PHONE_ALLOWLIST_ADDED"))
                .andExpect(jsonPath("$.events[0].result").value("DONE"));
    }

    @Test
    void listFiltersByExactEventTypeEndToEndAndExposesHonestCatalog() throws Exception {
        AdminSession session = login();
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), TENANT, "TASK_PROPOSED", json("""
                {"source":"company","principalId":"p-1","identityRealm":"%s","proposalId":"pr-1",
                 "taskId":"task-1","label":"周三巡检","kind":"CREATION","revision":1,
                 "expiresAt":"2026-11-05T00:00:00Z","at":"2026-11-04T01:04:00Z"}
                """.formatted(REALM_A)), Instant.parse("2026-11-04T01:04:00Z")));
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), TENANT, "TASK_CHANGE_PROPOSED", json("""
                {"source":"company","principalId":"p-1","identityRealm":"%s","proposalId":"pr-2",
                 "taskId":"task-1","kind":"CHANGE","revision":2,"at":"2026-11-04T01:05:00Z"}
                """.formatted(REALM_A)), Instant.parse("2026-11-04T01:05:00Z")));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("eventType", "TASK_PROPOSED")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventType").value("TASK_PROPOSED"))
                .andExpect(jsonPath("$.events[0].result").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // Valid shape but never emitted: an honest empty page, not an error and not a fake match.
        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("eventType", "SOME_FUTURE_TYPE")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(0));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("eventType", "not a type!")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUDIT_INVALID"));

        mockMvc.perform(get("/api/admin/audit/event-types")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTypes[?(@.type=='TASK_PROPOSED')].label")
                        .value("任务提案"))
                .andExpect(jsonPath("$.eventTypes[?(@.type=='ACTION_DRAFT_PARTIALLY_ACCEPTED')].label")
                        .value("动作部分执行"));
    }

    @Test
    void detailShowsCorrelatedIdsAndSeparatesReadbackFromUpstreamReceipts() throws Exception {
        AdminSession session = login();
        String eventId = UUID.randomUUID().toString();
        AUDIT.add(new AuditEventFact(eventId, TENANT, "ACTION_DRAFT_PARTIALLY_ACCEPTED", json("""
                {"source":"office","principalId":"p-1","identityRealm":"%s","draftId":"d-9",
                 "taskId":"task-9","proposalId":"pr-9","planId":"plan-9","reportId":"rep-9",
                 "cancellationId":"cx-9","conversationId":"c-9","turnId":"t-9","runId":"r-9",
                 "label":"下班关灯","kind":"lighting","operation":"EXECUTE","revision":4,
                 "status":"PARTIALLY_ACCEPTED","expiresAt":"2026-11-04T12:00:00Z",
                 "confirmedAt":"2026-11-04T01:06:00Z","creatorName":"李四","dispatchStarted":true,
                 "toolRawResponse":{"upstream":"raw"},"hiddenPlan":"internal",
                 "result":{"status":"PARTIALLY_ACCEPTED","message":"部分执行",
                   "receipts":[{"actionId":"a-1","outcome":"SUCCESS","reason":"ok"},
                               {"actionId":"a-2","outcome":"FAILED","reason":"device offline"}],
                   "readback":{"a-1":"on"},"observedStates":{"dev-2":{"raw":"dump"}}}}
                """.formatted(REALM_A)), Instant.parse("2026-11-04T01:06:30Z")));

        MvcResult result = mockMvc.perform(get("/api/admin/audit/" + eventId)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.draftId").value("d-9"))
                .andExpect(jsonPath("$.payload.taskId").value("task-9"))
                .andExpect(jsonPath("$.payload.proposalId").value("pr-9"))
                .andExpect(jsonPath("$.payload.planId").value("plan-9"))
                .andExpect(jsonPath("$.payload.reportId").value("rep-9"))
                .andExpect(jsonPath("$.payload.cancellationId").value("cx-9"))
                .andExpect(jsonPath("$.payload.creatorName").value("李四"))
                .andExpect(jsonPath("$.payload.confirmedAt").value("2026-11-04T01:06:00Z"))
                .andExpect(jsonPath("$.payload.result.receipts.length()").value(2))
                .andExpect(jsonPath("$.payload.result.receipts[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.payload.result.receipts[1].outcome").value("FAILED"))
                .andExpect(jsonPath("$.payload.result.readback.a-1").value("on"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("toolRawResponse").doesNotContain("hiddenPlan")
                .doesNotContain("observedStates");

        // A member-request event keeps correlated IDs and result sections explicitly
        // absent instead of fake-empty objects, so the console renders 未记录 states.
        String requestedId = AUDIT.events.stream()
                .filter(event -> "AGENT_REQUESTED".equals(event.eventType()))
                .findFirst().orElseThrow().eventId();
        MvcResult requested = mockMvc.perform(get("/api/admin/audit/" + requestedId)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.draftId").doesNotExist())
                .andExpect(jsonPath("$.payload.result").doesNotExist())
                .andReturn();
        assertThat(requested.getResponse().getContentAsString()).doesNotContain("receipts")
                .doesNotContain("readback");
    }

    @Test
    void systemExecutionEventsStayFilterableAndProjectSystemActorFacts() throws Exception {
        AdminSession session = login();
        String eventId = UUID.randomUUID().toString();
        // Scanner-shaped fact: system actor, no verified principal, checkpoint
        // identifiers and separated receipts/readback with honest partial counts.
        AUDIT.add(new AuditEventFact(eventId, TENANT, "TASK_CHECKPOINT_EXECUTED", json("""
                {"action":"execute_checkpoint","actor":"system","source":"office",
                 "taskId":"task-7","phaseId":"phase-7","status":"COMPLETED",
                 "label":"夜间巡查","creatorName":"王五","ownerKey":"owner-hash-7",
                 "conversationId":"c-7","reason":"按计划执行","dispatchStarted":true,
                 "counts":{"devices":2,"verified":1,"unconfirmed":1},
                 "result":{"receipts":[{"actionId":"a-7","outcome":"SUCCESS","reason":"ok"}],
                           "readback":{"dev-7":"confirmed","dev-8":"unconfirmed"}}}
                """), Instant.parse("2026-11-04T02:00:00Z")));
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), TENANT, "TASK_CHECKPOINT_EXECUTED", json("""
                {"action":"execute_checkpoint","actor":"system","source":"office",
                 "taskId":"task-7","phaseId":"phase-8","status":"UNCONFIRMED",
                 "counts":{"devices":1,"unconfirmed":1}}
                """), Instant.parse("2026-11-04T02:01:00Z")));

        // The scanner writes uppercase payload statuses: the COMPLETED result
        // filter must match them, not only the engine's run.completed token.
        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("result", "COMPLETED")
                        .param("eventType", "TASK_CHECKPOINT_EXECUTED")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].eventId").value(eventId))
                .andExpect(jsonPath("$.events[0].result").value("COMPLETED"));

        mockMvc.perform(get("/api/admin/audit")
                        .param("tenantCode", TENANT).param("eventType", "TASK_CHECKPOINT_EXECUTED")
                        .param("result", "UNCONFIRMED")
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].result").value("UNCONFIRMED"));

        mockMvc.perform(get("/api/admin/audit/" + eventId)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.actor").value("system"))
                .andExpect(jsonPath("$.payload.taskId").value("task-7"))
                .andExpect(jsonPath("$.payload.phaseId").value("phase-7"))
                .andExpect(jsonPath("$.payload.ownerKey").value("owner-hash-7"))
                .andExpect(jsonPath("$.payload.counts.devices").value(2))
                .andExpect(jsonPath("$.payload.result.receipts.length()").value(1))
                .andExpect(jsonPath("$.payload.result.readback.dev-8").value("unconfirmed"));

    }

    @Test
    void detailProjectsWhitelistOnlyAndMasksCredentialText() throws Exception {
        AdminSession session = login();
        String eventId = AUDIT.leakEventId;

        MvcResult result = mockMvc.perform(get("/api/admin/audit/" + eventId)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.source").value("office"))
                .andExpect(jsonPath("$.payload.result.status").value("RESOLVED"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("secret-token-xyz")
                .doesNotContain("hunter2secret")
                .doesNotContain("hidden chain of thought")
                .doesNotContain("toolRawResponse")
                .doesNotContain("observedStates")
                .doesNotContain("reasoning")
                .doesNotContain("authorization")
                .doesNotContain("leak-token-abc123");
        assertThat(body).doesNotContain("nested-secret-abc", "command-secret", "nested-password", "readback-secret");
        assertThat(body).contains("Bearer ***");

        mockMvc.perform(get("/api/admin/audit/" + AUDIT.draftEventId)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.actions[0].deviceId").value("dev-1"))
                .andExpect(jsonPath("$.payload.result.receipts[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.payload.result.readback.a-1").value("confirmed"))
                .andReturn();
        String draftBody = mockMvc.perform(get("/api/admin/audit/" + AUDIT.draftEventId)
                        .header("Cookie", session.cookie())).andReturn().getResponse().getContentAsString();
        assertThat(draftBody).doesNotContain("observedStates");
    }

    @Test
    void missingDetailReturns404() throws Exception {
        AdminSession session = login();
        mockMvc.perform(get("/api/admin/audit/does-not-exist").header("Cookie", session.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_NOT_FOUND"));
    }

    @Test
    void storageFailureSurfacesUnavailableInsteadOfEmptySuccess() throws Exception {
        AdminSession session = login();
        AUDIT.failure = new IllegalStateException("mysql down");

        mockMvc.perform(get("/api/admin/audit/tenants").header("Cookie", session.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
        mockMvc.perform(get("/api/admin/audit/members").param("tenantCode", TENANT)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
        mockMvc.perform(get("/api/admin/audit").param("tenantCode", TENANT)
                        .header("Cookie", session.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUDIT_UNAVAILABLE"));
    }

    private void seed() {
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), TENANT, "AGENT_REQUESTED", json("""
                {"source":"office","principalId":"p-1","identityRealm":"%s","displayName":"测试用户",
                 "mobileNumber":"13800000000","conversationId":"c-1","turnId":"t-1","runId":"r-1",
                 "action":"message","status":"started","request":"帮我看下三楼灯","at":"2026-11-04T01:00:00Z"}
                """.formatted(REALM_A)), Instant.parse("2026-11-04T01:00:00Z")));
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), TENANT, "AGENT_RUN_COMPLETED", json("""
                {"source":"office","principalId":"p-1","identityRealm":"%s","conversationId":"c-1",
                 "turnId":"t-1","runId":"r-1","status":"run.completed","reply":"已完成",
                 "at":"2026-11-04T01:00:30Z"}
                """.formatted(REALM_A)), Instant.parse("2026-11-04T01:00:30Z")));
        AUDIT.add(new AuditEventFact(AUDIT.draftEventId = UUID.randomUUID().toString(), TENANT,
                "ACTION_DRAFT_RESOLVED", json("""
                        {"source":"company","principalId":"p-1","identityRealm":"%s","draftId":"d-1",
                         "revision":2,"status":"RESOLVED","summary":"客厅灯已开",
                         "actions":[{"id":"a-1","deviceId":"dev-1","kind":"lighting","name":"客厅灯","command":{"closeOrOpen":1}}],
                         "result":{"status":"RESOLVED","message":"已执行","receipts":[{"actionId":"a-1","outcome":"SUCCESS","reason":"ok"}],
                                   "readback":{"a-1":"confirmed"},"observedStates":{"dev-1":{"raw":"dump"}}}}
                        """.formatted(REALM_B)), Instant.parse("2026-11-04T01:01:00Z")));
        AUDIT.add(new AuditEventFact(UUID.randomUUID().toString(), "ADMIN", "PHONE_ALLOWLIST_ADDED", json("""
                {"mobileNumber":"13800000000","name":"张三","actor":"admin","revision":2,
                 "addedAt":"2026-11-04T01:02:00Z"}
                """), Instant.parse("2026-11-04T01:02:00Z")));
        AUDIT.add(new AuditEventFact(AUDIT.leakEventId = UUID.randomUUID().toString(), TENANT,
                "ACTION_DRAFT_RESOLVED", json("""
                        {"source":"office","principalId":"p-1","identityRealm":"%s","draftId":"d-5",
                         "revision":3,"status":"RESOLVED","summary":"已处理 bearer abc123def456ghi",
                         "request":"password=hunter2secret 请处理",
                         "authorization":"Bearer secret-token-xyz",
                         "reasoning":"hidden chain of thought: internal scratch",
                         "toolRawResponse":{"upstream":"raw"},
                         "observedStates":{"dev-9":{"state":"raw"}},
                         "actions":[{"id":"a-9","deviceId":"dev-9","name":"Bearer nested-secret-abc",
                           "command":{"closeOrOpen":1,"authorization":"Bearer command-secret"},
                           "state":{"power":true,"raw":{"password":"nested-password"}}}],
                         "result":{"status":"RESOLVED","message":"Bearer leak-token-abc123",
                           "readback":{"a-9":"Bearer readback-secret"}}}
                        """.formatted(REALM_A)), Instant.parse("2026-11-04T01:03:00Z")));
    }

    private static String json(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private AdminSession login() throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/login")
                        .header("Origin", ORIGIN)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-test-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie").split(";")[0];
        String csrf = mapper.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText();
        return new AdminSession(cookie, csrf);
    }

    private record AdminSession(String cookie, String csrf) {
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        AuditEventStore fixedAuditEventStore() {
            return AUDIT;
        }
    }

    /** In-memory audit store; paging/grouping run on the interface defaults. */
    static final class InMemoryAudit implements AuditEventStore {
        final List<AuditEventFact> events = new ArrayList<>();
        volatile RuntimeException failure;
        String draftEventId;
        String leakEventId;

        void reset() {
            events.clear();
            failure = null;
            draftEventId = null;
            leakEventId = null;
        }

        void add(AuditEventFact event) {
            events.add(new AuditEventFact(event.eventId(), event.tenantCode(), event.eventType(),
                    event.payloadJson(), event.createdAt(), events.size() + 1));
        }

        @Override
        public AuditEventFact append(AuditEventFact event) {
            if (failure != null) throw failure;
            events.add(event);
            return event;
        }

        @Override
        public List<AuditEventFact> readByTenant(String tenantCode) {
            if (failure != null) throw failure;
            return events.stream().filter(event -> event.tenantCode().equals(tenantCode)).toList();
        }

        @Override
        public Optional<AuditEventFact> findByEventId(String eventId) {
            if (failure != null) throw failure;
            return events.stream().filter(event -> event.eventId().equals(eventId)).findFirst();
        }

        @Override
        public List<String> tenants() {
            if (failure != null) throw failure;
            return events.stream().map(AuditEventFact::tenantCode).distinct().sorted().toList();
        }
    }
}
