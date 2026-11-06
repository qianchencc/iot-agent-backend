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
import java.util.ArrayList;
import java.util.List;
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
    void timeOnlyEditKeepsTheExecutableBindingAndEquivalentTimeStillCreatesNoProposal() throws Exception {
        for (String actions : List.of("", """
                ,"actions":[{"kind":"lighting","deviceId":"lamp-1","command":{"closeOrOpen":1},"closeOrOpen":1}]
                """)) {
            ObjectMapper mapper = new ObjectMapper();
            TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
            TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
            TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
            TaskFacade facade = facade(plans, proposals, tasks);
            var original = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(plan("bound-plan").payloadJson());
            var phase = (com.fasterxml.jackson.databind.node.ObjectNode) original.path("phases").get(0);
            phase.put("requiresVerification", true);
            ((com.fasterxml.jackson.databind.node.ArrayNode) phase.path("actions")).add(mapper.readTree("""
                    {"kind":"lighting","deviceId":"lamp-1","controlRef":"authorized-control",
                     "bindingFingerprint":"verified-binding","command":{"closeOrOpen":1}}
                    """));
            when(plans.find("bound-plan")).thenReturn(Optional.of(
                    new TaskPlanFact("bound-plan", 1, "ENABLED", original.toString(), "bound-plan-key")));
            when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation", "time-edit",
                    """
                    {"target":{"type":"task_plan","planId":"bound-plan"},"operation":"edit",
                     "template":{"phases":[{"at":"2026-10-10T13:30:00+08:00"%s}]}}
                    """.formatted(actions), "time-edit");
            var afterPhase = mapper.readTree(proposal.afterJson()).path("phases").get(0);
            assertThat(afterPhase.path("at").asText()).isEqualTo("2026-10-10T13:30:00+08:00");
            var action = afterPhase.path("actions").get(0);
            assertThat(action.path("deviceId").asText()).isEqualTo("lamp-1");
            assertThat(action.path("command").path("closeOrOpen").asInt()).isEqualTo(1);
            assertThat(action.path("controlRef").asText()).isEqualTo("authorized-control");
            assertThat(action.path("bindingFingerprint").asText()).isEqualTo("verified-binding");
            assertThat(afterPhase.path("requiresVerification").asBoolean()).isTrue();
            assertThat(mapper.readTree(proposal.beforeJson()).path("phases").get(0).path("at").asText())
                    .isEqualTo("2026-10-09T10:00:00Z");
            assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation", "same-time",
                    """
                    {"target":{"type":"task_plan","planId":"bound-plan"},"operation":"edit",
                     "template":{"phases":[{"at":"2026-10-09T18:00:00+08:00"%s}]}}
                    """.formatted(actions), "same-time"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.httpStatus()).isEqualTo(400));
            verify(proposals).save(any());
            verify(plans, never()).updateIfVersion(any(), anyLong());
            verify(tasks, never()).save(any());
        }
    }

    @Test
    void timeOnlyEditCannotBorrowBindingsAcrossAChangedPhaseCount() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFacade facade = facade(plans, proposals, org.mockito.Mockito.mock(TaskFactStore.class));
        when(plans.find("phase-count")).thenReturn(Optional.of(plan("phase-count")));
        assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation", "phase-count",
                """
                {"target":{"type":"task_plan","planId":"phase-count"},"operation":"edit",
                 "template":{"phases":[{"at":"2026-10-10T10:00:00Z"},{"at":"2026-10-11T10:00:00Z"}]}}
                """, "phase-count"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(400));
        verify(proposals, never()).save(any());
    }

    @Test
    void editRequiresNonEmptyModificationBeforeSavingAProposal() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        when(plans.find("edit-plan")).thenReturn(Optional.of(plan("edit-plan")));
        for (String modification : List.of("", ",\"template\":null", ",\"template\":{}", ",\"template\":[]")) {
            assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation", "turn",
                    "{\"target\":{\"type\":\"task_plan\",\"planId\":\"edit-plan\"},\"operation\":\"edit\""
                            + modification + "}", "edit-missing"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.httpStatus()).isEqualTo(400));
        }
        verify(proposals, never()).save(any());
        verify(plans, never()).updateIfVersion(any(), anyLong());
    }

    @Test
    void equivalentEditDoesNotCreateAProposalForAliasesMetadataOrTimeNotation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        var original = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(plan("same-plan").payloadJson());
        original.put("label", "过道灯开启");
        ((com.fasterxml.jackson.databind.node.ObjectNode) original.path("phases").get(0)).put("phaseId", "original-phase");
        when(plans.find("same-plan")).thenReturn(Optional.of(
                new TaskPlanFact("same-plan", 1, "ENABLED", original.toString(), "same-plan-key")));
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThatThrownBy(() -> facade.proposeChange(PRINCIPAL, "office", "conversation", "turn",
                """
                {"target":{"type":"task_plan","planId":"same-plan"},"operation":"edit",
                 "template":{"label":"过道灯开启","normalizedAt":"2026-10-09T12:00:00Z",
                    "createdAt":"2026-10-09T12:00:00Z","checkpointCount":99,"taskKind":"recurring","phases":[
                    {"phaseId":"replacement-metadata","at":"2026-10-09T18:00:00+08:00","actions":[]}]}}
                """, "same-edit"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(400));
        verify(proposals, never()).save(any());
        verify(plans, never()).updateIfVersion(any(), anyLong());
    }

    @Test
    void renameOnlyEditPreservesScheduleAndDoesNotExecute() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        var original = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(plan("rename-only").payloadJson());
        original.put("label", "原名称");
        when(plans.find("rename-only")).thenReturn(Optional.of(
                new TaskPlanFact("rename-only", 1, "ENABLED", original.toString(), "rename-only-key")));
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation", "turn",
                """
                {"target":{"type":"task_plan","planId":"rename-only"},"operation":"edit",
                 "template":{"label":"新名称"}}
                """, "rename-only-edit");
        var after = mapper.readTree(proposal.afterJson());
        assertThat(after.path("label").asText()).isEqualTo("新名称");
        assertThat(mapper.readTree(proposal.beforeJson()).path("label").asText()).isEqualTo("原名称");
        assertThat(after.path("phases")).isEqualTo(original.path("phases"));
        assertThat(after.path("repeat")).isEqualTo(original.path("repeat"));
        verify(plans, never()).updateIfVersion(any(), anyLong());
        verify(tasks, never()).save(any());
    }

    @Test
    void reportVerificationOrObjectiveChangesRemainEffectiveEdits() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String change : List.of("report", "verification", "objective")) {
            TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
            TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
            TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
            TaskFacade facade = facade(plans, proposals, tasks);
            var original = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(plan("definition-plan").payloadJson());
            original.put("label", "检查任务");
            original.put("objective", "统计昨日");
            original.set("reportRequest", mapper.readTree("{\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) original.path("phases").get(0)).put("requiresVerification", false);
            when(plans.find("definition-plan")).thenReturn(Optional.of(
                    new TaskPlanFact("definition-plan", 1, "ENABLED", original.toString(), "definition-key")));
            when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            var template = mapper.createObjectNode();
            if ("report".equals(change)) {
                template.set("reportRequest", mapper.readTree("{\"period\":{\"kind\":\"previous\",\"unit\":\"week\"}}"));
            } else if ("verification".equals(change)) {
                var phases = original.path("phases").deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) phases.get(0)).put("requiresVerification", true);
                template.set("phases", phases);
            } else {
                template.put("objective", "统计上周");
            }
            var request = mapper.createObjectNode().put("operation", "edit");
            request.set("target", mapper.readTree("{\"type\":\"task_plan\",\"planId\":\"definition-plan\"}"));
            request.set("template", template);
            TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation", "turn",
                    request.toString(), change);
            var after = mapper.readTree(proposal.afterJson());
            assertThat(mapper.readTree(proposal.beforeJson())).isEqualTo(original);
            if ("report".equals(change)) {
                assertThat(after.path("reportRequest").path("period").path("unit").asText()).isEqualTo("week");
                assertThat(after.path("phases").get(0).path("requiresVerification").asBoolean()).isFalse();
            } else if ("verification".equals(change)) {
                assertThat(after.path("phases").get(0).path("requiresVerification").asBoolean()).isTrue();
                assertThat(after.path("reportRequest").path("period").path("unit").asText()).isEqualTo("day");
            } else {
                assertThat(after.path("objective").asText()).isEqualTo("统计上周");
                assertThat(after.path("phases")).isEqualTo(original.path("phases"));
            }
            verify(plans, never()).updateIfVersion(any(), anyLong());
            verify(tasks, never()).save(any());
        }
    }

    @Test
    void rescheduledPlanKeepsOldNameForReviewAndPersistsConsistentNewNameOnlyOnConfirmation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(plan("rename-plan").payloadJson());
        payload.put("label", "每天18:00开启过道灯");
        payload.put("title", "每天18:00开启过道灯");
        TaskPlanFact current = new TaskPlanFact("rename-plan", 1, "ENABLED", payload.toString(), "rename-plan-key");
        when(plans.find("rename-plan")).thenReturn(Optional.of(current));
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        AtomicReference<TaskProposalFact> saved = new AtomicReference<>();
        when(proposals.save(any())).thenAnswer(invocation -> {
            TaskProposalFact value = invocation.getArgument(0);
            saved.set(value);
            return value;
        });
        when(proposals.find(anyString())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(proposals.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        AtomicReference<TaskPlanFact> written = new AtomicReference<>();
        when(plans.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> {
            TaskPlanFact value = invocation.getArgument(0);
            written.set(value);
            return Optional.of(value);
        });

        TaskProposalFact proposal = facade.proposeChange(PRINCIPAL, "office", "conversation-rename", "turn-rename",
                """
                {"target":{"type":"task_plan","planId":"rename-plan"},"operation":"edit",
                 "template":{"label":"过道灯开启","phases":[{"at":"2026-10-10T01:30:00Z","actions":[]}]}}
                """, "rename-proposal");
        assertThat(mapper.readTree(proposal.beforeJson()).path("label").asText()).isEqualTo("每天18:00开启过道灯");
        assertThat(mapper.readTree(proposal.afterJson()).path("label").asText()).isEqualTo("过道灯开启");
        assertThat(mapper.readTree(proposal.afterJson()).path("title").asText()).isEqualTo("过道灯开启");
        verify(plans, never()).updateIfVersion(any(), anyLong());

        facade.confirmChange(PRINCIPAL, proposal.proposalId(), 1);
        var persisted = mapper.readTree(written.get().payloadJson());
        assertThat(persisted.path("label").asText()).isEqualTo("过道灯开启");
        assertThat(persisted.path("title").asText()).isEqualTo("过道灯开启");
        assertThat(persisted.path("phases").get(0).path("at").asText()).isEqualTo("2026-10-10T01:30:00Z");
        assertThat(persisted.path("repeat")).isEqualTo(payload.path("repeat"));
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
        assertThatThrownBy(() -> facade.updatePlan(PRINCIPAL, "plan-paused", 1, "resume", false, "office"))
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
    void restoredProposalExpiresAtItsBoundaryWithoutCreatingATaskOrReplacingTerminalState() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        TaskFacade facade = facade(plans, proposals, tasks);
        TaskProposalFact pending = new TaskProposalFact("expired-card", 3, "PENDING_CONFIRMATION",
                "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + PRINCIPAL.ownerKey() + "\"}",
                "proposal-key", "TASK", "{}", "{}", "{}", "create",
                "LOCAL", PRINCIPAL.ownerKey(), "office", NOW);
        when(proposals.find("expired-card")).thenReturn(Optional.of(pending));
        assertThat(facade.conversationProposalState(PRINCIPAL, "expired-card", "office"))
                .containsEntry("status", "EXPIRED").containsEntry("version", 3L)
                .containsEntry("expiresAt", NOW.toString());
        TaskProposalFact cancelled = new TaskProposalFact(pending.proposalId(), 4, "CANCELLED",
                pending.payloadJson(), pending.idempotencyKey(), pending.kind(), pending.targetJson(),
                pending.beforeJson(), pending.afterJson(), pending.operation(), pending.tenantCode(),
                pending.ownerKey(), pending.source(), pending.expiresAt());
        when(proposals.find("expired-card")).thenReturn(Optional.of(cancelled));
        assertThat(facade.conversationProposalState(PRINCIPAL, "expired-card", "office"))
                .containsEntry("status", "CANCELLED").containsEntry("version", 4L);
        verify(tasks, never()).save(any());
    }

    private static TaskFacade facade(TaskPlanStore plans, TaskProposalStore proposals, TaskFactStore tasks) {
        return new TaskFacade(new ObjectMapper(), tasks, proposals, plans,
                org.mockito.Mockito.mock(TaskNotificationStore.class), org.mockito.Mockito.mock(TaskScheduler.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void taskEditCancelsOnlyReplacedOldPhaseAndNeverSchedulesItAgain() {
        TaskPlanStore plans = org.mockito.Mockito.mock(TaskPlanStore.class);
        TaskProposalStore proposals = org.mockito.Mockito.mock(TaskProposalStore.class);
        TaskFactStore tasks = org.mockito.Mockito.mock(TaskFactStore.class);
        RecordingScheduler scheduler = new RecordingScheduler();
        TaskFacade facade = new TaskFacade(new ObjectMapper(), tasks, proposals, plans,
                org.mockito.Mockito.mock(TaskNotificationStore.class), scheduler,
                Clock.fixed(NOW, ZoneOffset.UTC));
        String owner = PRINCIPAL.ownerKey();
        String before = "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + owner
                + "\",\"phases\":[{\"phaseId\":\"keep\",\"at\":\"2026-10-10T10:00:00Z\",\"actions\":[]},"
                + "{\"phaseId\":\"replace\",\"at\":\"2026-10-10T11:00:00Z\",\"actions\":[]}]}";
        String after = "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\"" + owner
                + "\",\"phases\":[{\"phaseId\":\"keep\",\"at\":\"2026-10-10T10:00:00Z\",\"actions\":[]},"
                + "{\"phaseId\":\"replace\",\"at\":\"2026-10-10T12:00:00Z\",\"actions\":[]}]}";
        TaskFact current = new TaskFact("task-edit", 1, "SCHEDULED", before, "task-edit-key");
        TaskProposalFact proposal = new TaskProposalFact("proposal-edit", 1, "PENDING_CONFIRMATION", "{}",
                "idem-edit", "CHANGE", "{\"type\":\"task\",\"taskId\":\"task-edit\"}", before, after,
                "edit", "LOCAL", owner, "office", NOW.plusSeconds(60));
        when(tasks.find("task-edit")).thenReturn(Optional.of(current));
        when(proposals.find("proposal-edit")).thenReturn(Optional.of(proposal));
        when(proposals.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        when(tasks.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        facade.confirmChange(PRINCIPAL, "proposal-edit", 1);

        assertThat(scheduler.cancelled).containsExactly("replace");
        assertThat(scheduler.scheduled).hasSize(2).contains("keep").doesNotContain("replace");
    }

    private static final class RecordingScheduler implements TaskScheduler {
        private final List<String> scheduled = new ArrayList<>();
        private final List<String> cancelled = new ArrayList<>();
        @Override public void schedule(String taskId, String phaseId, Instant at) { scheduled.add(phaseId); }
        @Override public void cancel(String taskId, String phaseId) { cancelled.add(phaseId); }
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
