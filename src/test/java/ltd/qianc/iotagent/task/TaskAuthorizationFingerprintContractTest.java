package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskAuthorizationFingerprintContractTest {
    private static final Instant NOW = Instant.parse("2026-10-09T09:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");

    @Test
    void scopeFingerprintChangesWhenAControlBindingChanges() {
        AuthorizedDevice original = device("hub-1", "control-1");
        AuthorizedDevice changed = device("hub-1", "control-2");
        AuthorizedDeviceScope first = scope(original);
        AuthorizedDeviceScope second = scope(changed);

        assertThat(first.catalogFingerprint()).isEqualTo(second.catalogFingerprint());
        assertThat(first.bindingFingerprint()).isNotEqualTo(second.bindingFingerprint());
    }

    @Test
    void resumeRejectsAStaleBindingFingerprint() {
        AuthorizedDevice current = device("hub-1", "control-current");
        String payload = planPayload(scope(device("hub-1", "control-old")).bindingFingerprint());
        TaskPlanStore plans = mock(TaskPlanStore.class);
        when(plans.find("plan-1")).thenReturn(Optional.of(new TaskPlanFact("plan-1", 1, "PAUSED", payload, "plan-key")));
        IotAdapter iot = adapter(scope(current));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class), mock(TaskProposalStore.class),
                plans, mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, iot,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> facade.updatePlan(PRINCIPAL, "plan-1", 1, "resume", null, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("授权目录已变化");
    }

    @Test
    void resumeAcceptsTheCurrentBindingFingerprintAndUsesPlanCas() {
        AuthorizedDevice current = device("hub-1", "control-current");
        String payload = planPayload(scope(current).bindingFingerprint());
        TaskPlanStore plans = mock(TaskPlanStore.class);
        TaskPlanFact paused = new TaskPlanFact("plan-1", 1, "PAUSED", payload, "plan-key");
        when(plans.find("plan-1")).thenReturn(Optional.of(paused));
        when(plans.updateIfVersion(any(), anyLong())).thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class), mock(TaskProposalStore.class),
                plans, mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter(scope(current)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        TaskPlanFact resumed = facade.updatePlan(PRINCIPAL, "plan-1", 1, "resume", null, true);

        assertThat(resumed.status()).isEqualTo("ENABLED");
        assertThat(resumed.version()).isEqualTo(2);
    }

    private static IotAdapter adapter(AuthorizedDeviceScope scope) {
        return new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(scope);
            }
        };
    }

    private static AuthorizedDeviceScope scope(AuthorizedDevice device) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return new RequestIdentity("Bearer fixture", "LOCAL", IotSource.OFFICE); }
            @Override public IotSource source() { return IotSource.OFFICE; }
            @Override public List<AuthorizedDevice> devices() { return List.of(device); }
        };
    }

    private static AuthorizedDevice device(String hub, String control) {
        return new AuthorizedDevice("device-1", "lighting", "Light", "Room", false, control,
                "device-1", hub, "area-1", List.of(List.of("Room")), List.of(),
                Map.of("sourceKeys", List.of("microbrain.lighting")));
    }

    private static String planPayload(String bindingFingerprint) {
        return "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\""
                + PRINCIPAL.ownerKey() + "\",\"bindingFingerprint\":\"" + bindingFingerprint
                + "\",\"repeat\":{\"frequency\":\"daily\"},\"phases\":[{\"at\":\"2026-10-10T10:00:00Z\",\"actions\":[{\"deviceId\":\"device-1\",\"kind\":\"lighting\"}]}]}";
    }
}
