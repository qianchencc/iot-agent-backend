package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class TaskManagementContractTest {
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");

    @Test
    void occurrenceEditKeepsIdentityAndDoesNotMutateBeforeConfirmation() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskPlanFact plan = plan("plan-1");
        when(plans.find("plan-1")).thenReturn(Optional.of(plan));
        when(plans.findOccurrence("plan-1", java.time.LocalDate.of(2026, 10, 10))).thenReturn(Optional.empty());
        AtomicReference<TaskProposalFact> saved = new AtomicReference<>();
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> { TaskProposalFact value = invocation.getArgument(0); saved.set(value); return value; });

        TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation-1", "turn-1",
                "{\"target\":{\"type\":\"task\",\"taskId\":\"occurrence:plan-1:2026-10-10\"},"
                        + "\"operation\":\"edit\",\"template\":{\"phases\":[{\"at\":\"2026-10-10T16:30:00Z\",\"actions\":[]}]}}",
                "change-1");

        assertThat(saved.get()).isSameAs(proposal);
        assertThat(proposal.targetJson()).contains("occurrence:plan-1:2026-10-10");
        assertThat(proposal.beforeJson()).contains("2026-10-10T10:00:00Z");
        verify(plans, never()).overrideOccurrenceIfVersion(anyString(), any(), anyLong(), any(), any());
    }

    @Test
    void occurrenceSkipUsesOccurrenceCasAndReturnsStableReference() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskPlanFact plan = plan("plan-2");
        when(plans.find("plan-2")).thenReturn(Optional.of(plan));
        when(plans.findOccurrence("plan-2", java.time.LocalDate.of(2026, 10, 10))).thenReturn(Optional.empty());
        AtomicReference<TaskProposalFact> saved = new AtomicReference<>();
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> { TaskProposalFact value = invocation.getArgument(0); saved.set(value); return value; });
        when(proposals.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(proposals.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        TaskPlanOccurrence occurrence = new TaskPlanOccurrence("plan-2", java.time.LocalDate.of(2026, 10, 10), null,
                "SKIPPED", null, 1, 2);
        when(plans.overrideOccurrenceIfVersion("plan-2", java.time.LocalDate.of(2026, 10, 10), 1, null, NOW))
                .thenReturn(Optional.of(occurrence));

        TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation-2", "turn-2",
                "{\"target\":{\"type\":\"task\",\"taskId\":\"occurrence:plan-2:2026-10-10\"},\"operation\":\"skip\"}",
                "change-2");
        var result = facade.confirmChange(PRINCIPAL, proposal.proposalId(), 1);

        assertThat(result).containsEntry("planId", "plan-2");
        verify(plans).overrideOccurrenceIfVersion("plan-2", java.time.LocalDate.of(2026, 10, 10), 1, null, NOW);
    }

    @Test
    void occurrencePauseRemainsExplicitlyUnsupported() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        when(plans.find("plan-3")).thenReturn(Optional.of(plan("plan-3")));
        when(plans.findOccurrence("plan-3", java.time.LocalDate.of(2026, 10, 10))).thenReturn(Optional.empty());
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation-3", "turn-3",
                "{\"target\":{\"type\":\"task\",\"taskId\":\"occurrence:plan-3:2026-10-10\"},\"operation\":\"pause\"}", "change-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本次发生仅支持修改或跳过");
    }

    @Test
    void occurrenceEditRejectsPastChangedPhaseAtProposal() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        when(plans.find("plan-past")).thenReturn(Optional.of(plan("plan-past")));
        when(plans.findOccurrence("plan-past", java.time.LocalDate.of(2026, 10, 10))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation-past", "turn-past",
                "{\"target\":{\"type\":\"task\",\"taskId\":\"occurrence:plan-past:2026-10-10\"},"
                        + "\"operation\":\"edit\",\"template\":{\"phases\":[{\"at\":\"2026-10-09T08:59:00Z\",\"actions\":[]}]}}",
                "change-past"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须在未来");
    }

    @Test
    void planResumeRequiresExplicitConfirmationWhenPaused() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskPlanFact paused = new TaskPlanFact("plan-paused", 1, "PAUSED", plan("plan-paused").payloadJson(), "paused-key");
        when(plans.find("plan-paused")).thenReturn(Optional.of(paused));
        assertThatThrownBy(() -> facade.updatePlan(PRINCIPAL, "plan-paused", 1, "resume", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("确认当前范围");
    }

    @Test
    void sourceMismatchHidesTaskAndPlanAcrossOfficeAndCompanyRoutes() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        String owner = PRINCIPAL.ownerKey();
        TaskFact companyTask = new TaskFact("company-task", 1, "SCHEDULED",
                "{\"source\":\"company\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + owner + "\"}", "task-key");
        when(tasks.find("company-task")).thenReturn(Optional.of(companyTask));
        when(plans.find("company-plan")).thenReturn(Optional.of(new TaskPlanFact("company-plan", 1, "ENABLED",
                "{\"source\":\"company\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + owner + "\"}", "plan-key")));

        assertThatThrownBy(() -> facade.current(PRINCIPAL, "company-task", "office"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务不存在");
        assertThatThrownBy(() -> facade.cancel(PRINCIPAL, "company-task", 1, "office"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务不存在");
        assertThatThrownBy(() -> facade.plan(PRINCIPAL, "company-plan", "office"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("计划不存在");
        assertThatThrownBy(() -> facade.cancelPlan(PRINCIPAL, "office", "company-plan", 1, "all", null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("计划不存在");
        verify(tasks, never()).updateIfVersion(any(), anyLong());
        verify(plans, never()).cancelAllIfVersion(anyString(), anyLong(), any());
    }

    @Test
    void companyCancellationProposalLoadsTheTargetUsingTheRequestedSource() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        String owner = PRINCIPAL.ownerKey();
        TaskFact companyTask = new TaskFact("company-task", 1, "SCHEDULED",
                "{\"source\":\"company\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + owner
                        + "\",\"phases\":[]}", "company-key");
        when(tasks.find("company-task")).thenReturn(Optional.of(companyTask));
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TaskProposalFact proposal = facade.proposeCancellation(PRINCIPAL, "company", "conversation-company",
                "turn-company", "{\"target\":{\"type\":\"task\",\"taskId\":\"company-task\"}}", "cancel-company");

        assertThat(proposal.source()).isEqualTo("company");
        assertThat(proposal.targetJson()).contains("company-task");
        verify(tasks).find("company-task");
    }

    @Test
    void proposalIdempotencyCannotReplayAcrossSources() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskProposalFact prior = new TaskProposalFact("prior", 1, "PENDING_CONFIRMATION",
                "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + PRINCIPAL.ownerKey() + "\"}",
                "LOCAL\u0000" + PRINCIPAL.ownerKey() + "\u0000same", "TASK", "{}", "{}", "{}", "create",
                "LOCAL", PRINCIPAL.ownerKey(), "office", NOW.plusSeconds(60));
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.of(prior));

        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "company", "conversation", "turn", "{}", "same"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("幂等键");
        verify(proposals, never()).save(any());
    }

    @Test
    void taskViewProjectsRuntimeFieldsWithoutTenantOrOwnerIdentity() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskFact task = new TaskFact("task-view", 2, "COMPLETED",
                "{\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + PRINCIPAL.ownerKey()
                        + "\",\"source\":\"company\",\"label\":\"空调巡检\","
                        + "\"observedStates\":{\"device-1\":{\"power\":\"on\"}},"
                        + "\"report\":{\"generatedAt\":\"2026-10-12T01:00:00Z\",\"outcome\":\"completed\"}}",
                "task-view-key");

        Map<String, Object> view = facade.view(task);
        assertThat(view).containsEntry("label", "空调巡检")
                .containsEntry("status", "COMPLETED")
                .containsEntry("hasReport", true)
                .containsKey("observedStates");
        assertThat(view.get("payload").toString()).doesNotContain("tenantCode", PRINCIPAL.ownerKey());
    }

    private static TaskFacade facade(TaskPlanStore plans, TaskProposalStore proposals, TaskFactStore tasks) {
        return new TaskFacade(new ObjectMapper(), tasks, proposals, plans,
                org.mockito.Mockito.mock(TaskNotificationStore.class), org.mockito.Mockito.mock(TaskScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TaskPlanFact plan(String id) {
        String owner = PRINCIPAL.ownerKey();
        return new TaskPlanFact(id, 1, "ENABLED", "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\""
                + owner + "\",\"repeat\":{\"frequency\":\"daily\"},\"phases\":[{\"at\":\"2026-10-09T10:00:00Z\",\"actions\":[]}]}", id + "-key");
    }
    @Test
    void planPauseUsesParentCasAndLeavesOccurrenceIdentityToTheStore() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskPlanFact current = plan("plan-4");
        when(plans.find("plan-4")).thenReturn(Optional.of(current));
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        AtomicReference<TaskProposalFact> saved = new AtomicReference<>();
        when(proposals.save(any())).thenAnswer(invocation -> { TaskProposalFact value = invocation.getArgument(0); saved.set(value); return value; });
        when(proposals.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(proposals.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(plans.updateIfVersion(any(), anyLong())).thenReturn(Optional.of(new TaskPlanFact("plan-4", 2, "PAUSED",
                current.payloadJson(), current.idempotencyKey())));

        TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation-4", "turn-4",
                "{\"target\":{\"type\":\"task_plan\",\"planId\":\"plan-4\"},\"operation\":\"pause\"}", "change-4");
        facade.confirmChange(PRINCIPAL, proposal.proposalId(), 1);

        verify(plans).updateIfVersion(any(TaskPlanFact.class), org.mockito.ArgumentMatchers.eq(1L));
    }

}
