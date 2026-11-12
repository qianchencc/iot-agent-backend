package com.ehe.iotagent.admin.internal;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/** Compares the sidecar's actual report with saved settings; saving alone never proves adoption. */
final class AdminObservabilityStatus {
    private final AdminConfigurationStore store;
    private final WebClient sidecar;
    private final String token;

    AdminObservabilityStatus(AdminConfigurationStore store, WebClient sidecar, String token) {
        this.store = store;
        this.sidecar = sidecar;
        this.token = token;
    }

    Map<String, Object> current() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("reachable", false);
        status.put("enabled", null);
        status.put("adopted", false);
        status.put("detail", "未配置 sidecar 桥接地址，无法验证观测采用状态");
        if (sidecar == null) return status;
        Map<?, ?> health;
        try {
            health = sidecar.get().uri("/healthz")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve().bodyToMono(Map.class).block(Duration.ofMillis(1500));
        } catch (RuntimeException error) {
            status.put("detail", "无法连接 sidecar 读取观测采用状态");
            return status;
        }
        status.put("reachable", true);
        if (!(health != null && health.get("observability") instanceof Map<?, ?> reported
                && reported.get("enabled") instanceof Boolean reportedEnabled)) {
            status.put("detail", "sidecar 未上报完整观测状态");
            return status;
        }
        status.put("enabled", reportedEnabled);
        Map<String, Object> saved;
        try {
            saved = store.saved();
        } catch (RuntimeException error) {
            status.put("detail", "管理配置存储暂不可用，无法比对观测配置");
            return status;
        }
        boolean expectedEnabled = Boolean.TRUE.equals(saved.get("langfuseEnabled"))
                && text(saved.get("langfuseBaseUrl")) != null
                && text(saved.get("langfusePublicKey")) != null
                && text(saved.get("langfuseSecretKey")) != null;
        if (expectedEnabled != reportedEnabled) {
            status.put("detail", "已保存观测配置与 sidecar 当前采用不一致，需重启应用");
            return status;
        }
        if (!expectedEnabled) {
            status.put("adopted", true);
            status.put("detail", "观测已关闭，sidecar 确认未上报");
            return status;
        }
        boolean matches = String.valueOf(saved.get("langfuseBaseUrl")).equals(reported.get("baseUrl"))
                && credentialsDigest(saved.get("langfusePublicKey"), saved.get("langfuseSecretKey"))
                        .equals(reported.get("credentialsDigest"));
        status.put("adopted", matches);
        status.put("detail", matches ? "sidecar 已采用当前观测配置"
                : "观测地址或凭据已变化，需通过受监督重启后由 sidecar 采用");
        return status;
    }

    private static String credentialsDigest(Object publicKey, Object secretKey) {
        if (!(publicKey instanceof String pk) || !(secretKey instanceof String sk)) return "";
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest((pk + ":" + sk).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
