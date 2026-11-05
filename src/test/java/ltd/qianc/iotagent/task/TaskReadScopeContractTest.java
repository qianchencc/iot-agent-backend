package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Consumer-visible read scope contract: visibility by authorized devices, Runtime notification shape. */
class TaskReadScopeContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private final Queue<Runnable> scopeJobs = new ArrayDeque<>();

    @Test
    void settledCheckpointsDoNotPublishAReportUntilTheIntervalTaskSettles() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var identity = new ltd.qianc.iotagent.iot.RequestIdentity("Bearer report-reader", "TENANT", IotSourceEnum.COMPANY);
        TaskReadScope scope = TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("meter", "energy", "电表", "26楼", true))));
        var definition = mapper.readTree("{\"actions\":[{\"deviceId\":\"meter\",\"kind\":\"energy\"}]}");
        var result = mapper.readTree("{\"devices\":{\"meter\":{\"outcome\":\"verified\",\"observed\":18}}}");
        var phase = new TaskDetailProjector.PhaseRow("checkpoint", NOW.minusSeconds(60), "COMPLETED",
                definition, result, NOW.minusSeconds(60), NOW.minusSeconds(50), null);
        TaskDetailProjector projector = new TaskDetailProjector(mapper, taskId -> List.of(phase));
        var payload = mapper.readTree("{\"source\":\"company\",\"ownerKey\":\"owner\","
                + "\"interval\":{\"startsAt\":\"2026-11-01T23:00:00Z\",\"endsAt\":\"2026-11-02T01:00:00Z\"}}");
        var active = new TaskDetailProjector.TaskSource("interval-task", 2, "EXECUTING", payload,
                NOW.minusSeconds(3600), NOW);

        Map<String, Object> activeDetail = projector.detail(active, scope, "owner", Map.of());
        assertThat(activeDetail.get("report")).isNull();
        assertThat(projector.listSummary(activeDetail).get("hasReport")).isEqualTo(false);

        var settled = new TaskDetailProjector.TaskSource("interval-task", 3, "COMPLETED", payload,
                NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        Map<String, Object> settledDetail = projector.detail(settled, scope, "owner", Map.of());
        assertThat(((Map<?, ?>) settledDetail.get("report")).get("outcome")).isEqualTo("completed");
        assertThat(((Map<?, ?>) settledDetail.get("report")).get("generatedAt"))
                .isEqualTo(NOW.plusSeconds(3600).toString());
        assertThat(projector.listSummary(settledDetail).get("hasReport")).isEqualTo(true);
    }

    @Test
    void phaseObservationsTrimToTheAuthorizedScopeLikeTheReport() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // The creator keeps ac-1 and meter-1 but lost ac-2 and meter-2; the durable
        // facts still hold all four devices.
        var identity = new RequestIdentity("Bearer partial-owner", "TENANT", IotSourceEnum.COMPANY);
        TaskReadScope scope = TaskReadScope.of(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY,
                List.of(new AuthorizedDevice("ac-1", "ac", "机房空调", "26楼", true),
                        new AuthorizedDevice("meter-1", "energy", "机房电表", "配电室", true))));
        var acDefinition = mapper.readTree("{\"read\":{\"ac\":{\"mode\":\"adjust_to_26\",\"action\":{\"setpoint\":26}},"
                + "\"devices\":[{\"id\":\"ac-1\",\"kind\":\"ac\",\"name\":\"机房空调\",\"location\":\"26楼\"},"
                + "{\"id\":\"ac-2\",\"kind\":\"ac\",\"name\":\"会议室空调\",\"location\":\"27楼\"}]}}");
        var acResult = mapper.readTree("{\"devices\":{\"ac-1\":{\"outcome\":\"observed\"},"
                + "\"ac-2\":{\"outcome\":\"observed\"}},"
                + "\"observations\":["
                + "{\"type\":\"ac_inspection\",\"mode\":\"adjust_to_26\",\"criteria\":{\"setpoint\":{\"value\":26}},"
                + "\"observedAt\":\"2026-11-01T23:40:00Z\",\"devices\":["
                + "{\"deviceId\":\"ac-1\",\"outcome\":\"observed\",\"disposition\":\"adjusted\","
                + "\"setpoint\":24,\"observedAt\":\"2026-11-01T23:40:00Z\"},"
                + "{\"deviceId\":\"ac-2\",\"outcome\":\"observed\",\"disposition\":\"not_needed\","
                + "\"setpoint\":26,\"observedAt\":\"2026-11-01T23:40:00Z\"}]},"
                + "{\"type\":\"alerts\",\"windowDays\":7,\"total\":0,\"latest\":null,"
                + "\"windowLatest\":null,\"records\":[]}]}");
        var energyDefinition = mapper.readTree("{\"read\":{\"devices\":["
                + "{\"id\":\"meter-1\",\"kind\":\"energy\",\"name\":\"机房电表\",\"location\":\"配电室\"},"
                + "{\"id\":\"meter-2\",\"kind\":\"energy\",\"name\":\"会议室电表\",\"location\":\"27楼配电\"}]}}");
        var energyResult = mapper.readTree("{\"summary\":\"会议室电表用电平稳\","
                + "\"facts\":[{\"deviceId\":\"meter-2\",\"value\":31.4}],\"devices\":{"
                + "\"meter-1\":{\"outcome\":\"observed\"},\"meter-2\":{\"outcome\":\"observed\"}},"
                + "\"observations\":["
                + "{\"type\":\"energy_history\",\"report\":{\"meter\":{\"id\":\"meter-1\",\"name\":\"机房电表\"},"
                + "\"series\":[12.5,12.8]}},"
                + "{\"type\":\"energy_history\",\"report\":{\"meter\":{\"id\":\"meter-2\",\"name\":\"会议室电表\"},"
                + "\"series\":[30.1,31.4]}},"
                + "{\"type\":\"meter_report\",\"report\":{\"total\":31.4,\"name\":\"会议室电表\"}}]}");
        var phases = List.of(
                new TaskDetailProjector.PhaseRow("inspect", NOW.minusSeconds(3600), "COMPLETED",
                        acDefinition, acResult, NOW.minusSeconds(3600), NOW.minusSeconds(3000), null),
                new TaskDetailProjector.PhaseRow("energy", NOW.minusSeconds(2400), "COMPLETED",
                        energyDefinition, energyResult, NOW.minusSeconds(2400), NOW.minusSeconds(1800), null));
        TaskDetailProjector projector = new TaskDetailProjector(mapper, taskId -> phases);
        var task = new TaskDetailProjector.TaskSource("partial-task", 2, "COMPLETED",
                mapper.readTree("{\"source\":\"company\",\"ownerKey\":\"owner\"}"),
                NOW.minusSeconds(7200), NOW);

        Map<String, Object> detail = projector.detail(task, scope, "owner", Map.of());

        List<?> phaseViews = (List<?>) detail.get("phases");
        assertThat(phaseViews).hasSize(2);
        Map<?, ?> acView = (Map<?, ?>) phaseViews.get(0);
        assertThat(deviceIdsOf(acView)).containsExactly("ac-1");
        assertThat(observationDeviceIds(acView, "ac_inspection")).containsExactly("ac-1");
        assertThat(observationByType(acView, "alerts")).isNull();
        Map<?, ?> energyView = (Map<?, ?>) phaseViews.get(1);
        assertThat(deviceIdsOf(energyView)).containsExactly("meter-1");
        assertThat(observationByType(energyView, "energy_history")).isNotNull();
        assertThat(observationMeterIds(energyView)).containsExactly("meter-1");
        assertThat(energyView.get("agentResult")).isNull();
        assertThat(observationByType(energyView, "meter_report")).isNull();

        Map<?, ?> report = (Map<?, ?>) detail.get("report");
        List<?> reportPhases = (List<?>) report.get("phases");
        assertThat(reportPhases).hasSize(2);
        Map<?, ?> reportAc = (Map<?, ?>) reportPhases.get(0);
        assertThat(observationDeviceIds(reportAc, "ac_inspection")).containsExactly("ac-1");
        assertThat(observationByType(reportAc, "alerts")).isNull();
        assertThat(deviceIdsOf(reportAc)).containsExactly("ac-1");
        Map<?, ?> reportEnergy = (Map<?, ?>) reportPhases.get(1);
        assertThat(observationMeterIds(reportEnergy)).containsExactly("meter-1");
        assertThat(deviceIdsOf(reportEnergy)).containsExactly("meter-1");
        assertThat(observationByType(reportEnergy, "meter_report")).isNull();

        // The summary is fed only by the device that is still authorized.
        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertThat(summary.get("checkpoints")).isEqualTo(1L);
        Map<?, ?> summaryDevices = (Map<?, ?>) summary.get("devices");
        assertThat(summaryDevices.get("total")).isEqualTo(1L);
        assertThat(summaryDevices.get("adjusted")).isEqualTo(1L);
        assertThat(summaryDevices.get("satisfied")).isEqualTo(0L);

        // No revoked-device fact survives anywhere in the consumer-visible detail.
        assertThat(mapper.writeValueAsString(detail))
                .doesNotContain("ac-2")
                .doesNotContain("meter-2")
                .doesNotContain("会议室空调")
                .doesNotContain("会议室电表");
    }

    @Test
    void notificationsAndDetailTrimToTheAuthorizedScopeWithStableCursorShape() {
        PrincipalInfo principal = new PrincipalInfo("Bearer reader", "TENANT", "person", "gateway");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, List.of(
                new AuthorizedDevice("allowed", "energy", "表", "办公室", true))));
        String owner = principal.ownerKey();
        TaskFact visibleTask = new TaskFact("task-visible", 1, "COMPLETED",
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner + "\",\"label\":\"抄表任务\","
                        + "\"phases\":[{\"actions\":[{\"deviceId\":\"allowed\",\"kind\":\"energy\","
                        + "\"deviceName\":\"表\",\"location\":\"办公室\"}]}]}", "key-1");
        TaskFact foreignTask = new TaskFact("task-foreign", 1, "COMPLETED",
                "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"other\","
                        + "\"phases\":[{\"actions\":[{\"deviceId\":\"foreign\",\"kind\":\"energy\"}]}]}", "key-2");
        TaskFactStore tasks = mock(TaskFactStore.class);
        when(tasks.find("task-visible")).thenReturn(Optional.of(visibleTask));
        when(tasks.find("task-foreign")).thenReturn(Optional.of(foreignTask));
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        TaskNotificationFact visible = new TaskNotificationFact("notice-1", "task-visible", principal.tenantCode(),
                owner, "company", "COMPLETED", NOW.minusSeconds(60), null, "{\"outcome\":\"COMPLETED\"}");
        TaskNotificationFact foreign = new TaskNotificationFact("notice-2", "task-foreign", principal.tenantCode(),
                owner, "company", "COMPLETED", NOW, null, "{\"outcome\":\"COMPLETED\"}");
        when(notices.list(principal.tenantCode(), owner, "company", 60, null))
                .thenReturn(List.of(foreign, visible))
                .thenReturn(List.of());
        when(notices.unread(principal.tenantCode(), owner, "company")).thenReturn(List.of(foreign, visible));
        TaskPhaseStore phaseStore = mock(TaskPhaseStore.class);
        when(phaseStore.allPhases("task-visible")).thenReturn(List.of(new TaskPhaseStore.PhaseResult(
                "task-visible", "phase-visible", NOW.minusSeconds(60), "COMPLETED",
                "{\"actions\":[{\"deviceId\":\"allowed\",\"kind\":\"energy\",\"deviceName\":\"表\",\"location\":\"办公室\",\"command\":{}},"
                        + "{\"deviceId\":\"foreign\",\"kind\":\"energy\",\"deviceName\":\"范围外设备\"}]}",
                "{\"devices\":{\"allowed\":{\"outcome\":\"verified\",\"observed\":18},"
                        + "\"foreign\":{\"outcome\":\"verified\",\"observed\":99}}}", NOW.minusSeconds(60), NOW, null)));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), tasks, mock(TaskProposalStore.class),
                mock(TaskPlanStore.class), notices, mock(TaskScheduler.class), phaseStore, iot,
                snapshots(clock), clock);
        prepareScope(facade, principal, "company");

        Map<String, Object> result = facade.notificationsView(principal, "company", 30, null);

        assertThat(result.get("unreadCount")).isEqualTo(1L);
        assertThat(result.get("nextCursor")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("notifications");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("id")).isEqualTo("notice-1");
            assertThat(row.get("taskId")).isEqualTo("task-visible");
            assertThat(row.get("outcome")).isEqualTo("completed");
            assertThat(row.get("label")).isEqualTo("抄表任务");
            assertThat(row.get("readAt")).isNull();
        });

        Map<String, Object> detail = facade.taskFactView(principal, visibleTask, "company");
        assertThat((List<?>) detail.get("regionIds")).isEmpty();
        assertThat(detail.get("creator")).isEqualTo(Map.of("displayName", ""));
        assertThat(((Map<?, ?>) ((List<?>) detail.get("phases")).get(0)).get("devices"))
                .asList().singleElement().isEqualTo(
                        Map.of("id", "allowed", "kind", "energy", "name", "表", "location", "办公室",
                                "target", Map.of(), "outcome", "verified", "details", Map.of("outcome", "verified", "observed", 18)));
        assertThatThrownBy(() -> facade.taskFactView(principal, foreignTask, "company"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("TASK_NOT_FOUND"));
    }

    @Test
    void unavailableCatalogFailsClosedInsteadOfLeakingTenantWideRows() {
        PrincipalInfo principal = new PrincipalInfo("Bearer reader", "TENANT", "person", "gateway");
        IotAdapter iot = identity -> Mono.error(new IllegalStateException("upstream down"));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class),
                mock(TaskProposalStore.class), mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, iot,
                snapshots(Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        prepareScope(facade, principal, "company");

        assertThatThrownBy(() -> facade.notificationsView(principal, "company", 30, null))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("AUTHORIZED_SCOPE_UNAVAILABLE");
                    assertThat(error.httpStatus()).isEqualTo(503);
                });
    }

    @Test
    void sameSecondNotificationsPageByStableIdCursorAndMarkOnlyVisibleUnread() {
        PrincipalInfo principal = new PrincipalInfo("Bearer reader", "TENANT", "person", "gateway");
        Clock clock = new Clock() {
            @Override public Instant instant() { return NOW; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(NOW, zone); }
        };
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of(
                new AuthorizedDevice("allowed", "lighting", "灯", "办公室", false))));
        String owner = principal.ownerKey();
        TaskFact task = new TaskFact("task-1", 1, "PARTIAL",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner + "\","
                        + "\"phases\":[{\"actions\":[{\"deviceId\":\"allowed\",\"kind\":\"lighting\"}]}]}", "key");
        TaskFactStore tasks = mock(TaskFactStore.class);
        when(tasks.find("task-1")).thenReturn(Optional.of(task));
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        TaskNotificationFact first = new TaskNotificationFact("notice-a", "task-1", principal.tenantCode(),
                owner, "office", "PARTIAL", NOW, null, "{\"outcome\":\"PARTIAL\",\"reason\":\"部分失败\"}");
        TaskNotificationFact second = new TaskNotificationFact("notice-b", "task-1", principal.tenantCode(),
                owner, "office", "PARTIAL", NOW, null, "{\"outcome\":\"PARTIAL\"}");
        when(notices.find("notice-b")).thenReturn(Optional.of(second));
        when(notices.list(principal.tenantCode(), owner, "office", 60, null))
                .thenReturn(List.of(first, second));
        when(notices.list(principal.tenantCode(), owner, "office", 60, "notice-b"))
                .thenReturn(List.of(second));
        when(notices.unread(principal.tenantCode(), owner, "office")).thenReturn(List.of(first, second));
        when(notices.markSeen(principal.tenantCode(), owner, "office", "notice-b", NOW))
                .thenReturn(new TaskNotificationFact("notice-b", "task-1", principal.tenantCode(),
                        owner, "office", "PARTIAL", NOW, NOW, "{\"outcome\":\"PARTIAL\"}"));
        when(notices.markAllSeen(principal.tenantCode(), owner, "office", List.of("notice-a", "notice-b"), NOW)).thenReturn(2);
        TaskFacade facade = new TaskFacade(new ObjectMapper(), tasks, mock(TaskProposalStore.class),
                mock(TaskPlanStore.class), notices, mock(TaskScheduler.class), null, iot,
                snapshots(clock), clock);
        prepareScope(facade, principal, "office");

        Map<String, Object> firstPage = facade.notificationsView(principal, "office", 1, null);
        assertThat(firstPage.get("nextCursor")).isEqualTo("notice-a");
        Map<String, Object> secondPage = facade.notificationsView(principal, "office", 1, "notice-b");
        assertThat(secondPage.get("nextCursor")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) secondPage.get("notifications");
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("id")).isEqualTo("notice-b");
            assertThat(row.keySet()).doesNotContain("reason");
        });

        Map<String, Object> seen = facade.seenNotification(principal, "office", "notice-b");
        assertThat(seen).containsEntry("id", "notice-b").containsEntry("readAt", NOW.toString());
        Map<String, Object> seenAll = facade.seenAllNotifications(principal, "office");
        assertThat(seenAll).containsEntry("readAt", NOW.toString()).containsEntry("marked", 2);
    }

    @Test
    void unreadableOccurrenceOverridesFailInsteadOfShowingAnUnmodifiedRecurringPlan() {
        PrincipalInfo principal = new PrincipalInfo("Bearer reader", "TENANT");
        TaskPlanStore plans = mock(TaskPlanStore.class);
        when(plans.find("plan-1")).thenReturn(Optional.of(new TaskPlanFact("plan-1", 1, "ENABLED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + principal.ownerKey()
                        + "\",\"repeat\":{\"frequency\":\"daily\"},\"phases\":[]}", "plan-key")));
        when(plans.occurrences("plan-1")).thenThrow(new IllegalStateException("database unavailable"));
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of()));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class),
                mock(TaskProposalStore.class), plans, mock(TaskNotificationStore.class),
                mock(TaskScheduler.class), null, iot, snapshots(Clock.fixed(NOW, ZoneOffset.UTC)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        prepareScope(facade, principal, "office");

        assertThatThrownBy(() -> facade.planView(principal, "plan-1", "office"))
                .isInstanceOfSatisfying(BusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TASK_PLAN_UNAVAILABLE");
                    assertThat(failure.httpStatus()).isEqualTo(503);
                });
    }
    private ReadSnapshots snapshots(Clock clock) {
        return ReadSnapshots.create(clock, scopeJobs::add, scopeJobs::add,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
    }

    private static List<String> deviceIdsOf(Map<?, ?> phaseView) {
        List<String> ids = new ArrayList<>();
        for (Object device : (List<?>) phaseView.get("devices")) {
            ids.add(String.valueOf(((Map<?, ?>) device).get("id")));
        }
        return ids;
    }

    private static Map<?, ?> observationByType(Map<?, ?> phaseView, String type) {
        if (!(phaseView.get("observations") instanceof List<?> observations)) return null;
        for (Object block : observations) {
            if (block instanceof Map<?, ?> observation && type.equals(observation.get("type"))) {
                return observation;
            }
        }
        return null;
    }

    private static List<String> observationDeviceIds(Map<?, ?> phaseView, String type) {
        List<String> ids = new ArrayList<>();
        Map<?, ?> observation = observationByType(phaseView, type);
        if (observation == null || !(observation.get("devices") instanceof List<?> devices)) return ids;
        for (Object item : devices) {
            ids.add(String.valueOf(((Map<?, ?>) item).get("deviceId")));
        }
        return ids;
    }

    private static List<String> observationMeterIds(Map<?, ?> phaseView) {
        List<String> ids = new ArrayList<>();
        if (!(phaseView.get("observations") instanceof List<?> observations)) return ids;
        for (Object block : observations) {
            if (block instanceof Map<?, ?> observation && "energy_history".equals(observation.get("type"))
                    && observation.get("report") instanceof Map<?, ?> report
                    && report.get("meter") instanceof Map<?, ?> meter) {
                ids.add(String.valueOf(meter.get("id")));
            }
        }
        return ids;
    }

    private void prepareScope(TaskFacade facade, PrincipalInfo principal, String source) {
        try { facade.scope(principal, source); }
        catch (BusinessException pending) {
            if (!"TASK_READ_PREPARING".equals(pending.code())) throw pending;
        }
        while (!scopeJobs.isEmpty()) scopeJobs.remove().run();
    }
}
