package ltd.qianc.iotagent.iot.internal.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private Agent-tool HTTP boundary for read-only Office device operations. */
@RestController
@RequestMapping("/api/agent")
public final class IotDeviceController {
    private static final Duration ADAPTER_TIMEOUT = Duration.ofSeconds(120);
    private final IotAdapter adapter;

    public IotDeviceController(IotAdapter adapter) {
        this.adapter = adapter;
    }
    @GetMapping("/company-devices")
    public ResponseEntity<?> companyDevices(HttpServletRequest request) {
        ResponseEntity<?> source = requireCompanySource(request);
        if (source != null) return source;
        var principal = PrincipalContext.getPrincipalInfo();
        try {
            AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                    new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSource.COMPANY))
                    .block(ADAPTER_TIMEOUT);
            if (scope == null || scope.source() != IotSource.COMPANY) {
                throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "读取当前设备目录失败", 503);
            }
            List<Map<String, Object>> devices = scope.devices().stream().map(IotDeviceController::deviceView).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "company");
            body.put("observedAt", Instant.now().toString());
            body.put("devices", devices);
            return ResponseEntity.ok(body);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new UpstreamUnavailableException("读取当前设备目录");
        }
    }


    @GetMapping({"/devices/candidates", "/tools/find_device_candidates"})
    public ResponseEntity<?> candidates(
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestParam String kind,
            @RequestParam(required = false) String scopeText,
            @RequestParam(required = false, name = "scopeTerms") List<String> scopeTerms,
            @RequestParam(required = false, name = "nameHints") List<String> nameHints,
            @RequestParam(required = false) String deviceText) {
        requireOffice(source);
        DeviceCandidateQuery query = new DeviceCandidateQuery(kind, List.of(), scopeText,
                scopeTerms == null ? List.of() : scopeTerms,
                nameHints == null ? List.of() : nameHints, deviceText);
        return ResponseEntity.ok(adapter.findDeviceCandidates(identity(), query).block(ADAPTER_TIMEOUT));
    }

    @PostMapping({"/devices/states", "/devices/status", "/tools/read_device_states"})
    public ResponseEntity<?> states(
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestBody Map<String, Object> body) {
        requireOffice(source);
        List<String> ids = strings(body == null ? null : body.get("deviceIds"));
        return ResponseEntity.ok(adapter.readDeviceStates(identity(), ids).block(ADAPTER_TIMEOUT));
    }

    @PostMapping({"/devices/capabilities", "/tools/inspect_device_capabilities"})
    public ResponseEntity<?> capabilities(
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestBody Map<String, Object> body) {
        requireOffice(source);
        return ResponseEntity.ok(adapter.inspectDeviceCapabilities(identity(), parseCapabilityRequest(body)).block(ADAPTER_TIMEOUT));
    }

    private static ResponseEntity<?> requireCompanySource(HttpServletRequest request) {
        String[] values = request.getParameterValues("source");
        if (values == null || values.length != 1 || !"company".equals(values[0])) {
            return ResponseEntity.status(404).body(Map.of(
                    "code", "DEVICE_SOURCE_UNAVAILABLE",
                    "message", "设备目录仅支持公司来源；办公室设备经区域接口查询"));
        }
        return null;
    }

    private static Map<String, Object> deviceView(AuthorizedDevice device) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", device.id());
        view.put("name", device.name());
        view.put("kind", device.kind());
        view.put("location", device.installAddress());
        return view;
    }

    private RequestIdentity identity() {
        var principal = PrincipalContext.getPrincipalInfo();
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSource.OFFICE);
    }

    private static void requireOffice(String source) {
        if (!"office".equals(source)) throw new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备只读工具仅支持 Office 来源", 404);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
            result.add(text);
        }
        return result;
    }

    private static DeviceCapabilityRequest parseCapabilityRequest(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("candidateSets");
        if (!(raw instanceof List<?> sets) || sets.isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集无效");
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> result = new ArrayList<>();
        for (Object value : sets) {
            if (!(value instanceof Map<?, ?> set) || !(set.get("candidateSetId") instanceof String setId)) {
                throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集无效");
            }
            result.add(new DeviceCapabilityRequest.DeviceCapabilitySelection(setId, strings(set.get("deviceIds"))));
        }
        return new DeviceCapabilityRequest(result);
    }
}
