package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskReadScopeContractTest {
    @Test
    void heldReadScopeDoesNotBlockLiveNotificationFactsOrExposeForeignDevices() {
        PrincipalInfo principal = new PrincipalInfo("Bearer reader", "TENANT", "person", "gateway");
        MovingClock clock = new MovingClock();
        ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        AtomicBoolean failed = new AtomicBoolean();
        IotAdapter iot = identity -> Mono.fromSupplier(() -> {
            if (failed.get()) throw new BusinessException("SCOPE_UNAVAILABLE", "目录暂不可用", 503);
            return new AuthorizedDeviceScope() {
                public RequestIdentity identity() { return identity; }
                public IotSource source() { return identity.source(); }
                public List<AuthorizedDevice> devices() {
                    return List.of(new AuthorizedDevice("allowed", "energy", "表", "办公室", true));
                }
            };
        });
        TaskNotificationStore notices = mock(TaskNotificationStore.class);
        TaskNotificationFact fact = new TaskNotificationFact("notice", "task", principal.tenantCode(),
                principal.ownerKey(), "company", "COMPLETED", clock.instant(), null,
                "{\"report\":{\"meterIds\":[\"allowed\",\"foreign\"]}}");
        when(notices.list(principal.tenantCode(), principal.ownerKey(), "company", 30, null))
                .thenReturn(List.of(fact));
        when(notices.unreadCount(principal.tenantCode(), principal.ownerKey(), "company")).thenReturn(1L);
        try (ReadSnapshots snapshots = ReadSnapshots.create(clock, jobs::add, jobs::add, null, null,
                new ObjectMapper(), new ReadSnapshots.Settings(20, Duration.ofDays(2),
                        Duration.ofDays(7), Duration.ofSeconds(60)))) {
            TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class),
                    mock(TaskProposalStore.class), mock(TaskPlanStore.class), notices,
                    mock(TaskScheduler.class), null, iot, snapshots, clock);
            assertThatThrownBy(() -> facade.unreadNotificationCount(principal, "company"))
                    .isInstanceOfSatisfying(BusinessException.class, error -> {
                        assertThat(error.code()).isEqualTo("TASK_READ_PREPARING");
                        assertThat(error.httpStatus()).isEqualTo(503);
                    });
            jobs.remove().run();
            assertThat(facade.unreadNotificationCount(principal, "company")).isEqualTo(1);
            Map<String, Object> notification = facade.notifications(principal, "company", 30, null).get(0);
            Map<?, ?> report = (Map<?, ?>) notification.get("report");
            assertThat(report.get("meterIds")).isEqualTo(List.of("allowed"));
            assertThat(((Map<?, ?>) ((Map<?, ?>) notification.get("payload")).get("report")).get("meterIds"))
                    .isEqualTo(List.of("allowed"));
            Map<String, Object> detail = facade.view(principal, new TaskFact("task", 1, "COMPLETED",
                    fact.payloadJson(), "task-key"), "company");
            assertThat(((Map<?, ?>) detail.get("report")).get("meterIds")).isEqualTo(List.of("allowed"));
            assertThat(((Map<?, ?>) ((Map<?, ?>) detail.get("payload")).get("report")).get("meterIds"))
                    .isEqualTo(List.of("allowed"));

            // The next day may synchronize slowly or fail; durable notification facts still change immediately.
            failed.set(true);
            clock.now = clock.now.plus(Duration.ofDays(1));
            when(notices.unreadCount(principal.tenantCode(), principal.ownerKey(), "company")).thenReturn(2L);
            assertThat(facade.unreadNotificationCount(principal, "company")).isEqualTo(2);
            jobs.remove().run();
            assertThat(facade.unreadNotificationCount(principal, "company")).isEqualTo(2);
            assertThatThrownBy(() -> facade.unreadNotificationCount(principal, "office"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.code()).isEqualTo("TASK_READ_PREPARING"));
        }
    }

    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
