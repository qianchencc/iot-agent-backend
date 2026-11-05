package ltd.qianc.iotagent.agent.internal.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Startup snapshot of Java-owned observability settings for the sidecar.
 * Serves the process-start revision only: applying newer saved values requires
 * the supervised restart path, never a silent swap. Credentials travel solely
 * on this bridge-token-authenticated internal route and are never logged.
 */
@RestController
@RequestMapping("/internal/agent/observability")
public final class ObservabilityBridgeController {

    private final ObjectProvider<AdminConfigurationStore> stores;

    public ObservabilityBridgeController(ObjectProvider<AdminConfigurationStore> stores) {
        this.stores = stores;
    }

    @GetMapping
    public Map<String, Object> current() {
        AdminConfigurationStore store = stores.getIfAvailable();
        if (store == null) throw new BusinessException("ADMIN_CONFIG_UNAVAILABLE", "管理配置存储暂不可用", 503);
        Map<String, Object> active = store.active();
        Map<String, Object> response = new LinkedHashMap<>();
        boolean enabled = Boolean.TRUE.equals(active.get("langfuseEnabled"))
                && text(active.get("langfuseBaseUrl")) != null
                && text(active.get("langfusePublicKey")) != null
                && text(active.get("langfuseSecretKey")) != null;
        response.put("enabled", enabled);
        response.put("revision", store.view(new AdminConfigurationStore.DeploymentInfo("", 0, "", "", "")).activeRevision());
        if (enabled) {
            response.put("baseUrl", active.get("langfuseBaseUrl"));
            response.put("publicKey", active.get("langfusePublicKey"));
            response.put("secretKey", active.get("langfuseSecretKey"));
        }
        return response;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
