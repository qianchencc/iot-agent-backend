package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskReadLatencyContractTest {
    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer center-reader", "TENANT", "member", "gateway");

    @Test
    void coldReadReportsPreparationAndHeldAuthorizationDoesNotDelayNewLocalNotifications() {
        try (Harness h = new Harness()) {
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING");
                        assertThat(failure.httpStatus()).isEqualTo(503);
                    });
            h.runUpstream();
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-1");

            // A new local result must be visible without waiting for another upstream crawl.
            h.upstreamAvailable = false;
            h.inbox.set(List.of(h.notification("notice-2"), h.notification("notice-1")));
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-2", "notice-1");
        }
    }

    @Test
    void failedScopeRenewalFailsClosedAndSuccessfulRenewalRemovesRevokedDevices() {
        try (Harness h = new Harness()) {
            h.prepare();
            h.clock.now = NOW.plusSeconds(61);
            h.upstreamAvailable = false;
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-1");
            h.runUpstream();
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("AUTHORIZED_SCOPE_UNAVAILABLE"));

            h.clock.now = NOW.plusSeconds(122);
            h.devices.set(List.of(new AuthorizedDevice("light-1", "lighting", "灯一", "26楼", false)));
            h.upstreamAvailable = true;
            assertThatThrownBy(() -> h.facade.scope(PRINCIPAL, "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("AUTHORIZED_SCOPE_UNAVAILABLE"));
            h.runUpstream();
            assertThat(h.facade.scope(PRINCIPAL, "company").deviceIds()).containsExactly("light-1");
        }
    }

    @Test
    void cancellationRechecksLiveAuthorizationInsteadOfTrustingTheHeldReadScope() {
        try (Harness h = new Harness()) {
            h.prepare();
            h.devices.set(List.of(new AuthorizedDevice("light-1", "lighting", "灯一", "26楼", false)));
            assertThatThrownBy(() -> h.facade.cancel(PRINCIPAL, "task-1", 1, "company"))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("TASK_FORBIDDEN");
                        assertThat(failure.httpStatus()).isEqualTo(403);
                    });
            assertThat(h.tasks.find("task-1").orElseThrow().status()).isEqualTo("SCHEDULED");
        }
    }

    @Test
    void heldReadAuthorizationIsIsolatedByCredentialAndSourceAndCannotSurviveRevocation() {
        try (Harness h = new Harness()) {
            h.prepare();
            PrincipalInfo rotated = new PrincipalInfo("Bearer rotated-reader", "TENANT", "member", "gateway");
            assertThatThrownBy(() -> h.facade.scope(rotated, "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            assertThatThrownBy(() -> h.facade.scope(PRINCIPAL, "office"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            h.snapshots.revoke(new ReadSnapshots.Identity("TENANT", PRINCIPAL.authorization(), "COMPANY",
                    PRINCIPAL.ownerKey()), new BusinessException("UNAUTHORIZED", "身份已失效", 401));
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.httpStatus()).isEqualTo(401));
        }
    }

    @Test
    void liveCredentialDenialImmediatelyRevokesTheHeldReadScope() {
        try (Harness h = new Harness()) {
            h.prepare();
            h.upstreamDenial = new BusinessException("UNAUTHORIZED", "上游身份已失效", 401);
            assertThatThrownBy(() -> h.facade.cancel(PRINCIPAL, "task-1", 1, "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.httpStatus()).isEqualTo(401));
            assertThatThrownBy(() -> h.facade.notificationsView(PRINCIPAL, "company", 30, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.httpStatus()).isEqualTo(401));
        }
    }

    @Test
    void backgroundCompositionPreparesTheSameVerifiedScopeUsedByOrdinaryReads() {
        try (Harness h = new Harness()) {
            TaskReadScope scope = h.facade.loadScope(new RequestIdentity(PRINCIPAL.authorization(),
                    PRINCIPAL.tenantCode(), ltd.qianc.iotagent.iot.IotSourceEnum.COMPANY, PRINCIPAL.ownerKey()));
            assertThat(scope.deviceIds()).containsExactlyInAnyOrder("light-1", "light-2");
            h.upstreamAvailable = false;
            assertThat(rows(h.facade.notificationsView(PRINCIPAL, "company", 30, null)))
                    .extracting(row -> row.get("id")).containsExactly("notice-1");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("notifications");
    }

    private static final class Harness implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final Queue<Runnable> upstream = new ArrayDeque<>();
        final Queue<Runnable> storage = new ArrayDeque<>();
        final AtomicReference<List<TaskNotificationFact>> inbox = new AtomicReference<>();
        final AtomicReference<List<AuthorizedDevice>> devices = new AtomicReference<>(List.of(
                new AuthorizedDevice("light-1", "lighting", "灯一", "26楼", false),
                new AuthorizedDevice("light-2", "lighting", "灯二", "26楼", false)));
        final TaskFactStore tasks = mock(TaskFactStore.class);
        final AtomicReference<TaskFact> task = new AtomicReference<>();
        final ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream::add, storage::add,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
        final TaskFacade facade;
        boolean upstreamAvailable = true;
        BusinessException upstreamDenial;

        Harness() {
            IotAdapter iot = identity -> {
                if (upstreamDenial != null) return Mono.error(upstreamDenial);
                return upstreamAvailable
                        ? Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices.get()))
                        : Mono.error(new IllegalStateException("upstream unavailable"));
            };
            String payload = "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\""
                    + PRINCIPAL.ownerKey() + "\",\"label\":\"关灯任务\",\"phases\":[{\"actions\":["
                    + "{\"deviceId\":\"light-1\",\"kind\":\"lighting\"},{\"deviceId\":\"light-2\",\"kind\":\"lighting\"}]}]}";
            task.set(new TaskFact("task-1", 1, "SCHEDULED", payload, "task-key"));
            when(tasks.find("task-1")).thenAnswer(invocation -> Optional.of(task.get()));
            when(tasks.updateIfVersion(any(TaskFact.class), anyLong())).thenAnswer(invocation -> {
                TaskFact next = invocation.getArgument(0);
                long expected = invocation.getArgument(1);
                TaskFact current = task.get();
                return current.version() == expected && task.compareAndSet(current, next)
                        ? Optional.of(next) : Optional.empty();
            });
            TaskNotificationStore notifications = mock(TaskNotificationStore.class);
            inbox.set(List.of(notification("notice-1")));
            when(notifications.list(anyString(), anyString(), anyString(), anyInt(), nullable(String.class)))
                    .thenAnswer(invocation -> inbox.get());
            when(notifications.unread(anyString(), anyString(), anyString())).thenAnswer(invocation -> inbox.get());
            facade = new TaskFacade(new ObjectMapper(), tasks, mock(TaskProposalStore.class),
                    mock(TaskPlanStore.class), notifications, mock(TaskScheduler.class), null, iot, snapshots, clock);
        }

        TaskNotificationFact notification(String id) {
            return new TaskNotificationFact(id, "task-1", "TENANT", PRINCIPAL.ownerKey(), "company",
                    "COMPLETED", NOW, null, "{\"outcome\":\"COMPLETED\"}");
        }

        void prepare() {
            try { facade.scope(PRINCIPAL, "company"); }
            catch (BusinessException pending) {
                if (!"TASK_READ_PREPARING".equals(pending.code())) throw pending;
            }
            runUpstream();
        }

        void runUpstream() {
            while (!upstream.isEmpty()) upstream.remove().run();
        }

        @Override public void close() { snapshots.close(); }
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
