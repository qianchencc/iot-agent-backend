package ltd.qianc.iotagent.admin.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.allowlist.AllowedPhone;
import ltd.qianc.iotagent.allowlist.PhoneAllowlist;
import ltd.qianc.iotagent.allowlist.PhoneAllowlistView;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机号白名单管理端点；会话、Origin 与 CSRF 由既有 AdminBoundaryFilter 统一核验。
 * 名单用于公司任务资格；不代替身份认证或设备授权。
 */
@RestController
@RequestMapping("/api/admin/allowlist")
public final class AllowlistAdminController {

    private final PhoneAllowlist allowlist;

    public AllowlistAdminController(ObjectProvider<PhoneAllowlist> provider) {
        this.allowlist = provider.getIfAvailable();
    }

    /** Returns revision and phone-only entries; no login or device rights are granted. */
    @GetMapping
    public ResponseEntity<?> list() {
        if (allowlist == null) return error(503, "ALLOWLIST_UNAVAILABLE", "白名单存储不可用，请稍后重试");
        try {
            PhoneAllowlistView view = allowlist.list();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("revision", view.revision());
            body.put("users", publicUsers(view.users()));
            return ResponseEntity.ok(body);
        } catch (BusinessException error) {
            return error(error.httpStatus(), error.code(), error.getMessage());
        }
    }

    /** Adds a phone and optional name at expectedRevision; invalid input is 400 and conflicts 409. */
    @PostMapping
    public ResponseEntity<?> add(@RequestBody(required = false) Map<String, Object> body) {
        if (allowlist == null) return error(503, "ALLOWLIST_UNAVAILABLE", "白名单存储不可用，请稍后重试");
        Entry entry = entry(body);
        if (entry == null) return error(400, "ALLOWLIST_INVALID", "请提供手机号和当前版本号");
        try {
            AllowedPhone user = allowlist.add(entry.expectedRevision(),
                    entry.mobileNumber(), entry.name(), "operator");
            return ResponseEntity.ok(Map.of("user", publicUser(user)));
        } catch (BusinessException failure) {
            return error(failure.httpStatus(), failure.code(), failure.getMessage());
        }
    }

    /** Removes a phone at expectedRevision; returns ok without cancelling any business tasks. */
    @PostMapping("/remove")
    public ResponseEntity<?> remove(@RequestBody(required = false) Map<String, Object> body) {
        if (allowlist == null) return error(503, "ALLOWLIST_UNAVAILABLE", "白名单存储不可用，请稍后重试");
        Entry entry = entry(body);
        if (entry == null) return error(400, "ALLOWLIST_INVALID", "请提供手机号和当前版本号");
        try {
            allowlist.remove(entry.expectedRevision(), entry.mobileNumber(), "operator");
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BusinessException failure) {
            return error(failure.httpStatus(), failure.code(), failure.getMessage());
        }
    }

    private static List<Map<String, Object>> publicUsers(List<AllowedPhone> users) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (AllowedPhone user : users) view.add(publicUser(user));
        return view;
    }

    private static Map<String, Object> publicUser(AllowedPhone user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("mobileNumber", user.mobileNumber());
        view.put("name", user.name());
        view.put("addedBy", user.addedBy());
        view.put("addedAt", user.addedAt());
        return view;
    }

    private static Entry entry(Map<String, Object> body) {
        if (body == null) return null;
        String mobileNumber = text(body, "mobileNumber");
        String name = text(body, "name");
        Long expectedRevision = longValue(body, "expectedRevision");
        if (mobileNumber == null || expectedRevision == null) {
            return null;
        }
        return new Entry(mobileNumber, name, expectedRevision);
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String text ? text : null;
    }

    private static Long longValue(Map<String, Object> body, String key) {
        return ltd.qianc.iotagent.error.RequestValues.optionalLong(body, key, 0,
                "ALLOWLIST_INVALID", "请提供有效版本号", 400);
    }

    private ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    private record Entry(String mobileNumber, String name, long expectedRevision) {
    }
}
