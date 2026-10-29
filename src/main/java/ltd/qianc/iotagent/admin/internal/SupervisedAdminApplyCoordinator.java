package ltd.qianc.iotagent.admin.internal;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.admin.AdminApplyCoordinator;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.admin.AdminWorkState;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/** Applies only after a deployment supervisor explicitly accepts the handoff. */
public final class SupervisedAdminApplyCoordinator implements AdminApplyCoordinator {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final AdminConfigurationStore store;
    private final AdminWorkState workState;
    private final WebClient supervisor;
    private final String token;

    public SupervisedAdminApplyCoordinator(AdminConfigurationStore store, AdminWorkState workState,
            WebClient supervisor, String token) {
        this.store = Objects.requireNonNull(store, "store");
        this.workState = Objects.requireNonNull(workState, "workState");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.token = token == null ? "" : token;
    }

    @Override
    public boolean enabled() {
        return !token.isBlank();
    }

    @Override
    public ApplyResult apply() {
        if (!enabled()) return unavailable();
        if (!store.pendingRestart()) return new ApplyResult(409, "CONFIG_NO_PENDING", "当前没有待生效的配置");
        if (workState.busy()) return new ApplyResult(409, "CONFIG_APPLY_BUSY", safeReason());
        try {
            Map<?, ?> result = supervisor.post().uri("/internal/admin/apply")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-Admin-Supervisor-Token", token)
                    .bodyValue(Map.of("handoff", "restart"))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);
            if (result == null || !Boolean.TRUE.equals(result.get("accepted"))) return unavailable();
            return new ApplyResult(200, "CONFIG_APPLY_SCHEDULED", "配置已提交给受监督进程，后端将重启并加载新配置");
        } catch (Exception ignored) {
            return unavailable();
        }
    }

    private static String safeReason() {
        return "当前有在途工作，请完成后再立即生效";
    }

    private static ApplyResult unavailable() {
        return new ApplyResult(503, "CONFIG_APPLY_UNAVAILABLE", "受监督进程未接受配置应用，请稍后重试");
    }
}
