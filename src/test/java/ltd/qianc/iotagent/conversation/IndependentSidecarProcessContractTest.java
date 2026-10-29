package ltd.qianc.iotagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import ltd.qianc.iotagent.admin.AdminApplyCoordinator;
import ltd.qianc.iotagent.admin.AdminApplyCoordinatorFactory;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.admin.AdminWorkState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

/** Optional local acceptance against separately launched Java and Node processes. */
@EnabledIfEnvironmentVariable(named = "AGENT_PROCESS_SMOKE", matches = "true")
class IndependentSidecarProcessContractTest {
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void closeExecutor() { executor.shutdownNow(); }

    @Test
    void JavaBridgeReceivesSidecarNdjsonAndSupervisorAcceptsHandoff() throws Exception {
        String baseUrl = env("AGENT_PROCESS_SIDECAR_URL", "http://127.0.0.1:3221");
        String token = required("AGENT_BRIDGE_TOKEN");
        String supervisorToken = required("AGENT_SUPERVISOR_TOKEN");
        List<AgentEngineEvent> events = new ArrayList<>();
        AgentEngineBridge bridge = AgentEngineBridgeFactory.http(new ObjectMapper(), baseUrl, token, executor,
                Duration.ofSeconds(45));
        bridge.start(new AgentRunRequest("java-process-run", "java-process-conversation", "java-process-turn",
                "office", "你好", "LOCAL", null, null, "java-process-context"), events::add)
                .toCompletableFuture().get();
        assertThat(events).isNotEmpty();
        assertThat(events.get(events.size() - 1).type()).startsWith("run.");

        AdminConfigurationStore store = mock(AdminConfigurationStore.class);
        when(store.pendingRestart()).thenReturn(true);
        AdminWorkState work = new AdminWorkState() {
            @Override public boolean busy() { return false; }
            @Override public String reason() { return ""; }
        };
        AdminApplyCoordinator coordinator = AdminApplyCoordinatorFactory.supervised(store, work,
                WebClient.builder().baseUrl(baseUrl).build(), supervisorToken);
        AdminApplyCoordinator.ApplyResult result = coordinator.apply();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.code()).isEqualTo("CONFIG_APPLY_SCHEDULED");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
