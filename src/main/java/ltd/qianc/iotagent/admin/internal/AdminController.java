package ltd.qianc.iotagent.admin.internal;

import jakarta.servlet.http.HttpServletRequest;
import ltd.qianc.iotagent.admin.AdminApplyCoordinator;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.admin.AdminPasswordPolicy;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public final class AdminController {
    private final AdminSessionManager sessions;
    private final AdminConfigurationStore store;
    private final AdminApplyCoordinator apply;
    private final AdminModelTester modelTester;
    private final String configuredPassword;
    private final Path passwordFile;
    private final boolean secureCookie;
    @Value("${server.address:127.0.0.1}")
    private String deploymentHost;
    @Value("${server.port:3200}")
    private int deploymentPort;
    @Value("${spring.profiles.active:local}")
    private String deploymentEnvironment;

    public AdminController(AdminSessionManager sessions, AdminConfigurationStore store, AdminApplyCoordinator apply,
            AdminModelTester modelTester,
            @Value("${admin.origin:${ADMIN_ORIGIN:http://127.0.0.1:3200}}") String origin,
            @Value("${admin.password:${ADMIN_PASSWORD:}}") String configuredPassword,
            @Value("${admin.password-file:${ADMIN_PASSWORD_FILE:var/admin-password}}") String passwordFile) {
        this.sessions = sessions;
        this.store = store;
        this.apply = apply;
        this.modelTester = modelTester;
        this.secureCookie = origin.startsWith("https:");
        this.configuredPassword = configuredPassword;
        this.passwordFile = Path.of(passwordFile);
    }

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

    @GetMapping("/password")
    public Map<String, Object> password() {
        return Map.of("editable", configuredPassword == null || configuredPassword.isBlank(), "minimumLength", sessions.minimum());
    }

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        if (configuredPassword != null && !configuredPassword.isBlank()) return error(409, "ADMIN_PASSWORD_DEPLOYMENT_MANAGED", "密码由部署环境管理，请修改部署密码");
        String current = text(body, "currentPassword");
        String next = text(body, "newPassword");
        String confirmation = text(body, "confirmPassword");
        if (next == null || !next.equals(confirmation)) return error(400, "ADMIN_PASSWORD_MISMATCH", "两次新密码不一致");
        try {
            AdminPasswordPolicy.validate(next, sessions.minimum());
        } catch (IllegalArgumentException e) {
            return error(400, "ADMIN_PASSWORD_INVALID", e.getMessage());
        }
        if (!sessions.matchesCurrent(current)) return error(400, "ADMIN_PASSWORD_INVALID", "当前密码不正确");
        AdminSessionManager.PasswordResult result = sessions.changePassword(current, next, confirmation, request.getRemoteAddr(), passwordFile);
        if (result.status() != 200) return error(result.status(), result.code(), result.message());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie("", 0)).body(Map.of("ok", true, "message", "密码已修改，请使用新密码重新登录"));
    }

    @GetMapping("/config")
    public AdminConfigurationStore.ConfigurationView config() {
        return view();
    }

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
        if (!apply.enabled()) return error(409, "CONFIG_APPLY_UNAVAILABLE", "此部署未启用立即生效，请手动重启后端");
        if (!store.pendingRestart()) return error(409, "CONFIG_NO_PENDING", "当前没有待生效的配置");
        AdminApplyCoordinator.ApplyResult result = apply.apply();
        return ResponseEntity.status(result.status()).body(Map.of("code", result.code(), "message", result.message()));
    }

    @GetMapping("/changes")
    public Map<String, Object> changes() {
        return Map.of("changes", store.changes());
    }

    @GetMapping("/prompts")
    public Map<String, Object> prompts() {
        Map<String, Object> active = store.active();
        return Map.of("revision", view().activeRevision(), "office", active.getOrDefault("officeInstructions", ""), "company", active.getOrDefault("companyInstructions", ""));
    }

    @PostMapping("/restore")
    public ResponseEntity<?> restore(@RequestBody(required = false) Map<String, Object> body) {
        Long expected = longValue(body, "expectedRevision");
        Long revision = longValue(body, "revision");
        if (expected == null || revision == null) return error(400, "CONFIG_INVALID", "请选择有效版本");
        AdminConfigurationStore.SaveResult result = store.restore(expected, revision, "operator");
        if (result.status() != 200) return saveError(result);
        return ResponseEntity.ok(view());
    }

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
        return new AdminConfigurationStore.ConfigurationView(
                current.revision(), current.activeRevision(), current.saved(), current.active(),
                current.secrets(), current.locked(), current.pendingRestart(), current.changedFields(),
                apply.enabled(), current.deployment());
    }

    private ResponseEntity<Map<String, Object>> saveError(AdminConfigurationStore.SaveResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", result.code());
        body.put("message", switch (result.code()) {
            case "CONFIG_VERSION_CONFLICT" -> "配置已被其他操作修改，请刷新后重试";
            case "CONFIG_ENV_LOCKED" -> "环境锁定字段不能修改";
            case "CONFIG_REVISION_NOT_FOUND" -> "配置版本不存在";
            default -> "配置无效，请检查修改字段";
        });
        if (!result.issues().isEmpty()) body.put("issues", result.issues());
        return ResponseEntity.status(result.status()).body(body);
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    private String cookie(String token, int maxAge) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(AdminSessionManager.COOKIE, token)
                .path("/api/admin").httpOnly(true).sameSite("Strict").maxAge(maxAge);
        if (secureCookie) builder.secure(true);
        return builder.build().toString();
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value instanceof String text ? text : null;
    }

    private static Long longValue(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Number number && number.doubleValue() == number.longValue()) return number.longValue();
        return null;
    }

    private static String loginMessage(String code) {
        return switch (code) {
            case "ADMIN_LOGIN_FAILED" -> "管理员密码不正确";
            case "ADMIN_RATE_LIMIT" -> "尝试次数过多，请稍后重试";
            default -> "管理控制台暂不可用，请稍后重试";
        };
    }
}
