package ltd.qianc.iotagent.admin.internal;

import jakarta.servlet.http.HttpServletRequest;
import ltd.qianc.iotagent.admin.AdminApplyCoordinator;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.admin.PiSessionOperations;
import ltd.qianc.iotagent.admin.PiSessionOperationsFactory;
import ltd.qianc.iotagent.instructions.AgentInstructions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Independent administrator HTTP contract; business Bearer credentials do not grant access.
 * Mutations require the configured Origin, the session Cookie and X-Admin-CSRF (except login).
 * Configuration writes carry an expectedRevision and object changes; responses are secret-redacted
 * ConfigurationViews. Restore creates a new revision and apply reports adoption separately.
 * See docs/api/java-contract.md for request fields, response fields and failure codes.
 */
@RestController
@RequestMapping("/api/admin")
public final class AdminController {
    private final AdminSessionManager sessions;
    private final AdminConfigurationStore store;
    private final AdminApplyCoordinator apply;
    private final AdminModelTester modelTester;
    private final boolean secureCookie;
    private org.springframework.web.reactive.function.client.WebClient sidecar;
    private String sidecarToken = "";
    private PiSessionOperations piSession = PiSessionOperationsFactory.unavailable();
    @Value("${server.address:127.0.0.1}")
    private String deploymentHost;
    @Value("${server.port:3200}")
    private int deploymentPort;
    @Value("${spring.profiles.active:local}")
    private String deploymentEnvironment;
    @Value("${agent.action-draft-ttl-ms:600000}")
    private long deploymentDraftTtlMs;
    @Value("${iot.gateway.readback-wait-ms:6000}")
    private long deploymentReadbackWaitMs;
    @Value("${iot.gateway.call-timeout-ms:30000}")
    private long deploymentControlTimeoutMs;

    public AdminController(AdminSessionManager sessions, AdminConfigurationStore store, AdminApplyCoordinator apply,
            AdminModelTester modelTester,
            @Value("${admin.origin:${ADMIN_ORIGIN:http://127.0.0.1:3200}}") String origin) {
        this.sessions = sessions;
        this.store = store;
        this.apply = apply;
        this.modelTester = modelTester;
        this.secureCookie = origin.startsWith("https:");
    }

    @Autowired(required = false)
    void setPiSessionOperations(PiSessionOperations operations) {
        if (operations != null) this.piSession = operations;
    }

    @Autowired(required = false)
    void setSidecarHealth(org.springframework.beans.factory.ObjectProvider<
            org.springframework.web.reactive.function.client.WebClient.Builder> builders,
            @Value("${agent.bridge.base-url:}") String baseUrl,
            @Value("${agent.bridge.token:}") String token) {
        if (baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) return;
        org.springframework.web.reactive.function.client.WebClient.Builder builder =
                builders.getIfAvailable(org.springframework.web.reactive.function.client.WebClient::builder);
        if (builder == null) return;
        this.sidecar = builder.baseUrl(baseUrl).build();
        this.sidecarToken = token;
    }

    /** Accepts a password string; returns an HttpOnly session Cookie and csrfToken, or 401/429/503. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        Object value = body == null ? null : body.get("password");
        AdminSessionManager.LoginResult result = sessions.login(request.getRemoteAddr(), value instanceof String text ? text : null);
        if (result.status() != 200) return error(result.status(), result.code(), loginMessage(result.code()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(result.token(), result.maxAge())).body(Map.of("csrfToken", result.csrf()));
    }

    @GetMapping("/session")
    public Map<String, String> session(HttpServletRequest request) {
        return Map.of("csrfToken", sessions.csrf(AdminBoundaryFilter.token(request)));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        sessions.logout(AdminBoundaryFilter.token(request));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", 0)).body(Map.of("ok", true));
    }

    /** Returns saved/active revisions, redacted values, field metadata and actual runtime adoption. */
    @GetMapping("/config")
    public AdminConfigurationStore.ConfigurationView config() {
        return view();
    }

    /** Saves object changes at expectedRevision; conflicts are 409 and invalid fields are 400. */
    @PatchMapping("/config")
    public ResponseEntity<?> save(@RequestBody(required = false) Map<String, Object> body) {
        Long expected = longValue(body, "expectedRevision");
        Object raw = body == null ? null : body.get("changes");
        if (expected == null || !(raw instanceof Map<?, ?>)) return error(400, "CONFIG_INVALID", "配置版本与修改字段无效");
        Map<String, Object> changes = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((key, value) -> { if (key instanceof String name) changes.put(name, value); });
        AdminConfigurationStore.SaveResult result = store.save(expected, changes, "operator");
        if (result.status() != 200) return saveError(result);
        return ResponseEntity.ok(view());
    }

    @PostMapping("/apply")
    public ResponseEntity<?> apply() {
        AdminApplyCoordinator.ApplyResult result = apply.apply();
        return ResponseEntity.status(result.status()).body(Map.of("code", result.code(), "message", result.message()));
    }

    @GetMapping("/pi-session/health")
    public ResponseEntity<?> piSessionHealth() {
        return operation(piSession.health());
    }

    @GetMapping("/changes")
    public Map<String, Object> changes() {
        return Map.of("changes", store.changes());
    }

    @GetMapping("/prompts")
    public Map<String, Object> prompts() {
        AdminConfigurationStore.ConfigurationView current = view();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", current.revision());
        AgentInstructions.preview(current.active()).forEach(result::put);
        return result;
    }

    /** Restores a selected historical revision as a new revision, guarded by expectedRevision. */
    @PostMapping("/restore")
    public ResponseEntity<?> restore(@RequestBody(required = false) Map<String, Object> body) {
        Long expected = longValue(body, "expectedRevision");
        Long revision = longValue(body, "revision");
        if (expected == null || revision == null) return error(400, "CONFIG_INVALID", "请选择有效版本");
        AdminConfigurationStore.SaveResult result = store.restore(expected, revision, "operator");
        if (result.status() != 200) return saveError(result);
        return ResponseEntity.ok(view());
    }

    /** Tests candidate connection changes without saving them; connection failures return 502. */
    @PostMapping("/test-model")
    public ResponseEntity<?> testModel(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("changes");
        if (!(raw instanceof Map<?, ?>)) return error(400, "CONFIG_INVALID", "连接参数无效");
        Map<String, Object> candidate = new LinkedHashMap<>(store.saved());
        ((Map<?, ?>) raw).forEach((key, value) -> {
            if (key instanceof String name) candidate.put(name, value);
        });
        try {
            modelTester.test(candidate);
            return ResponseEntity.ok(Map.of("ok", true, "message", "模型连接成功"));
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return error(502, "CONFIG_TEST_FAILED", "模型连接失败");
        }
    }

    private AdminConfigurationStore.DeploymentInfo deployment() {
        return new AdminConfigurationStore.DeploymentInfo(deploymentHost, deploymentPort, "0.1.0",
                deploymentEnvironment, java.time.ZoneId.systemDefault().getId());
    }

    private AdminConfigurationStore.ConfigurationView view() {
        AdminConfigurationStore.ConfigurationView current = store.view(deployment());
        Map<String, Object> deploymentSettings = new LinkedHashMap<>();
        deploymentSettings.put("draftTtlMs", deploymentDraftTtlMs);
        deploymentSettings.put("readbackWaitMs", deploymentReadbackWaitMs);
        deploymentSettings.put("controlTimeoutMs", deploymentControlTimeoutMs);
        return new AdminConfigurationStore.ConfigurationView(
                current.revision(), current.activeRevision(), current.saved(), current.active(),
                current.secrets(), current.locked(), current.pendingRestart(), current.changedFields(),
                apply.enabled(), current.deployment(), current.fields(), deploymentSettings,
                Map.of("observability", observabilityRuntime()));
    }

    private ResponseEntity<Map<String, Object>> saveError(AdminConfigurationStore.SaveResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", result.code());
        body.put("message", switch (result.code()) {
            case "CONFIG_VERSION_CONFLICT" -> "配置已被其他操作修改，请刷新后重试";
            case "CONFIG_ENV_LOCKED" -> "环境锁定字段不能修改";
            case "CONFIG_REVISION_NOT_FOUND" -> "配置版本不存在";
            case "CONFIG_READONLY_DEPLOYMENT" -> "部署参数只读，请在部署配置中修改后重启";
            default -> "配置无效，请检查修改字段";
        });
        if (!result.issues().isEmpty()) body.put("issues", result.issues());
        return ResponseEntity.status(result.status()).body(body);
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    /**
     * Truthful observability adoption state: asks the sidecar what it actually
     * runs and compares with the saved snapshot. A saved revision or a
     * supervisor acknowledgement alone never counts as adopted.
     */
    private Map<String, Object> observabilityRuntime() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("reachable", false);
        status.put("enabled", null);
        status.put("adopted", false);
        status.put("detail", "未配置 sidecar 桥接地址，无法验证观测采用状态");
        if (sidecar == null) return status;
        Map<?, ?> health;
        try {
            health = sidecar.get().uri("/healthz")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + sidecarToken)
                    .retrieve().bodyToMono(Map.class)
                    .block(java.time.Duration.ofMillis(1500));
        } catch (RuntimeException error) {
            status.put("detail", "无法连接 sidecar 读取观测采用状态");
            return status;
        }
        status.put("reachable", true);
        if (!(health != null && health.get("observability") instanceof Map<?, ?> reported)) {
            status.put("detail", "sidecar 未上报观测状态");
            return status;
        }
        boolean reportedEnabled = Boolean.TRUE.equals(reported.get("enabled"));
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
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest((pk + ":" + sk).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            return "";
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private ResponseEntity<?> operation(PiSessionOperations.OperationResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", result.code());
        body.put("message", result.message());
        Map<String, Object> details = new LinkedHashMap<>();
        for (String key : new String[] {"status", "backend", "scopes", "documents", "invalidJournals"}) {
            Object value = result.details().get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) details.put(key, value);
        }
        if (!details.isEmpty()) body.put("details", details);
        return ResponseEntity.status(result.status()).body(body);
    }

    private String cookie(String token, int maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(AdminSessionManager.COOKIE, token)
                .path("/api/admin").httpOnly(true).sameSite("Strict").maxAge(maxAge);
        if (secureCookie) builder.secure(true);
        return builder.build().toString();
    }


    private static Long longValue(Map<String, Object> body, String key) {
        return ltd.qianc.iotagent.error.RequestValues.optionalLong(body, key, 0,
                "CONFIG_INVALID", "配置版本无效", 400);
    }

    private static String loginMessage(String code) {
        return switch (code) {
            case "ADMIN_LOGIN_FAILED" -> "管理员密码不正确";
            case "ADMIN_RATE_LIMIT" -> "尝试次数过多，请稍后重试";
            case "ADMIN_UNAVAILABLE" -> "管理员凭据未配置或无效，请联系部署人员在部署配置中设置 admin.password 后重启";
            default -> "管理控制台暂不可用，请联系部署人员检查后端配置";
        };
    }
}
