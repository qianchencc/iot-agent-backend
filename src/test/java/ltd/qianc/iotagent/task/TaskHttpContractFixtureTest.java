package ltd.qianc.iotagent.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.IotAgentBackendApplication;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Public HTTP contract fixtures; no controller implementation type is referenced. */
@SpringBootTest(classes = IotAgentBackendApplication.class,
        properties = {"iot.identity.verify=false", "agent.bridge.token=test-bridge"})
@AutoConfigureMockMvc
class TaskHttpContractFixtureTest {
    private static final String AUTH = "Bearer task-fixture";
    private static final String TENANT = "TASK-TENANT";

    @Autowired private MockMvc http;
    @MockBean private TaskFacade facade;
    @MockBean private TaskProjectionStore projections;

    @Test
    void proposalReplayUsesStableShapeAndForwardsSourceAndIdempotency() throws Exception {
        TaskProposalFact proposal = new TaskProposalFact("proposal-1", 1, "PENDING_CONFIRMATION",
                "{\"source\":\"company\",\"title\":\"night\"}", "idem-1");
        when(facade.propose(any(), eq("company"), eq("conversation-1"), eq("turn-1"), anyString(), eq("idem-1")))
                .thenReturn(proposal);
        when(facade.proposalView(proposal)).thenReturn(Map.of("proposalId", "proposal-1", "version", 1,
                "status", "PENDING_CONFIRMATION", "payload", Map.of("source", "company")));
        for (int i = 0; i < 2; i++) {
            http.perform(post("/api/agent/task-proposals").header("Authorization", AUTH).header("Tenantcode", TENANT)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"company\",\"conversationId\":\"conversation-1\",\"turnId\":\"turn-1\",\"idempotencyKey\":\"idem-1\",\"payload\":{\"title\":\"night\"}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.proposalId").value("proposal-1"))
                    .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                    .andExpect(jsonPath("$.payload.source").value("company"));
        }
        verify(facade, org.mockito.Mockito.times(2)).propose(any(), eq("company"), eq("conversation-1"), eq("turn-1"), anyString(), eq("idem-1"));
    }

    @Test
    void staleVersionAndTerminalFailuresExposeStableErrorBody() throws Exception {
        doThrow(new BusinessException("VERSION_CONFLICT", "任务状态已变化，请刷新后再试", "请刷新", 409))
                .when(facade).cancel(any(), eq("task-1"), eq(3L));
        http.perform(post("/api/agent/tasks/task-1/cancel").header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("任务状态已变化，请刷新后再试")).andExpect(jsonPath("$.recovery").value("请刷新"));
        doThrow(new BusinessException("TASK_ALREADY_FINISHED", "任务已结束", 409)).when(facade).cancel(any(), eq("task-1"), eq(4L));
        http.perform(post("/api/agent/tasks/task-1/cancel").header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_ALREADY_FINISHED"));
    }

    @Test
    void planChangeAndCancellationRoutesKeepKindsAndVersions() throws Exception {
        TaskProposalFact change = new TaskProposalFact("change-1", 2, "PENDING_CONFIRMATION", "{\"operation\":\"edit\"}", "change-key", "CHANGE", "{\"id\":\"task-1\"}", "{}", "{}", "edit", TENANT, "fixture", "office", null);
        TaskProposalFact cancellation = new TaskProposalFact("cancel-1", 4, "PENDING_CONFIRMATION", "{}", "cancel-key", "CANCELLATION", "{\"type\":\"task\",\"id\":\"task-1\"}", "{}", "{}", "cancel", TENANT, "fixture", "office", null);
        TaskPlanFact plan = new TaskPlanFact("plan-1", 3, "SCHEDULED", "{\"source\":\"office\"}", "plan-key");
        when(facade.proposeChange(any(), eq("office"), eq("conversation-1"), eq("turn-2"), anyString(), eq("change-key"))).thenReturn(change);
        when(facade.proposeCancellation(any(), eq("office"), eq("conversation-1"), eq("turn-3"), anyString(), eq("cancel-key"))).thenReturn(cancellation);
        when(facade.proposalView(change)).thenReturn(Map.of("proposalId", "change-1", "version", 2, "status", "PENDING_CONFIRMATION", "kind", "CHANGE"));
        when(facade.proposalView(cancellation)).thenReturn(Map.of("proposalId", "cancel-1", "version", 4, "status", "PENDING_CONFIRMATION", "kind", "CANCELLATION"));
        when(facade.createPlan(any(), eq("office"), anyString(), eq("plan-key"))).thenReturn(plan);
        when(facade.planView(plan)).thenReturn(Map.of("planId", "plan-1", "version", 3, "status", "SCHEDULED"));
        http.perform(post("/api/agent/task-changes").header("Authorization", AUTH).header("Tenantcode", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"office\",\"conversationId\":\"conversation-1\",\"turnId\":\"turn-2\",\"idempotencyKey\":\"change-key\",\"operation\":\"edit\",\"target\":{\"id\":\"task-1\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.proposalId").value("change-1")).andExpect(jsonPath("$.version").value(2));
        http.perform(post("/api/agent/task-cancellations").header("Authorization", AUTH).header("Tenantcode", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"office\",\"conversationId\":\"conversation-1\",\"turnId\":\"turn-3\",\"idempotencyKey\":\"cancel-key\",\"target\":{\"type\":\"task\",\"id\":\"task-1\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("CANCELLATION"));
        http.perform(post("/api/agent/task-plans").header("Authorization", AUTH).header("Tenantcode", TENANT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"office\",\"idempotencyKey\":\"plan-key\",\"payload\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.planId").value("plan-1")).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void timelineCalendarNotificationsAndAuthHaveStableContracts() throws Exception {
        when(projections.regionTimeline(any(), eq("office"), eq("region-1"), any(Instant.class), any(Instant.class))).thenReturn(List.of(Map.of("taskId", "task-1")));
        http.perform(get("/api/agent/regions/region-1/timeline").header("Authorization", AUTH).header("Tenantcode", TENANT).queryParam("date", "2026-10-30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.date").value("2026-10-30")).andExpect(jsonPath("$.tasks[0].taskId").value("task-1"));
        http.perform(get("/api/agent/devices/device-1/calendar").header("Authorization", AUTH).header("Tenantcode", TENANT).queryParam("from", "2026-10-30").queryParam("to", "2027-01-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        when(facade.notifications(any(), eq("office"), eq(2), eq(null))).thenReturn(List.of(Map.of("id", "notice-1")));
        when(facade.unreadNotificationCount(any(), eq("office"))).thenReturn(1L);
        http.perform(get("/api/agent/notifications").header("Authorization", AUTH).header("Tenantcode", TENANT).queryParam("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.notifications[0].id").value("notice-1"));
        http.perform(get("/api/agent/tasks")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
