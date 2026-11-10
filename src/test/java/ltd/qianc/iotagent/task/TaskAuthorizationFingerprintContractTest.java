package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import ltd.qianc.iotagent.iot.IotSourceEnum;
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
        AuthorizedDevice original = device("hub-1", "control-old");
        String payload = planPayload(scope(original).bindingFingerprint(), original.bindingFingerprint());
        TaskPlanStore plans = mock(TaskPlanStore.class);
        when(plans.find("plan-1")).thenReturn(Optional.of(new TaskPlanFact("plan-1", 1, "PAUSED", payload, "plan-key")));
        IotAdapter iot = adapter(scope(current));
        TaskFacade facade = new TaskFacade(new ObjectMapper(), mock(TaskFactStore.class), mock(TaskProposalStore.class),
                plans, mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, iot,
                mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> facade.updatePlan(PRINCIPAL, "plan-1", 1, "resume", true, "office"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("控制绑定已变化");
    }

    private static IotAdapter adapter(AuthorizedDeviceScope scope) {
        return new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(scope);
            }
            @Override public Mono<ltd.qianc.iotagent.iot.DeviceDirectoryBatch> readDirectory(RequestIdentity identity) {
                return Mono.just(new ltd.qianc.iotagent.iot.DeviceDirectoryBatch(scope, List.of()));
            }
        };
    }

    private static AuthorizedDeviceScope scope(AuthorizedDevice device) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return new RequestIdentity("Bearer fixture", "LOCAL", IotSourceEnum.OFFICE); }
            @Override public IotSourceEnum source() { return IotSourceEnum.OFFICE; }
            @Override public List<AuthorizedDevice> devices() { return List.of(device); }
        };
    }

    private static AuthorizedDevice device(String hub, String control) {
        return new AuthorizedDevice("device-1", "lighting", "Light", "Room", false, control,
                "device-1", hub, "area-1", List.of(List.of("Room")), List.of(),
                Map.of("sourceKeys", List.of("microbrain.lighting")));
    }

    private static String planPayload(String bindingFingerprint, String targetBinding) {
        return "{\"source\":\"office\",\"tenantCode\":\"LOCAL\",\"ownerKey\":\""
                + PRINCIPAL.ownerKey() + "\",\"bindingFingerprint\":\"" + bindingFingerprint
                + "\",\"repeat\":{\"frequency\":\"daily\"},\"phases\":[{\"at\":\"2026-10-10T10:00:00Z\",\"actions\":[{\"deviceId\":\"device-1\",\"kind\":\"lighting\",\"bindingFingerprint\":\""
                + targetBinding + "\"}]}]}";
    }
}
