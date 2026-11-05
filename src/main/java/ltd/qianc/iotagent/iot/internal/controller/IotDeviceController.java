package ltd.qianc.iotagent.iot.internal.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private Agent-tool HTTP boundary for read-only Office and Company device operations. */
@RestController
@RequestMapping("/api/agent")
public final class IotDeviceController {
    private static final Duration ADAPTER_TIMEOUT = Duration.ofSeconds(120);
    private final IotAdapter adapter;
    private final ReadSnapshots snapshots;
    private final ObjectMapper mapper;

    public IotDeviceController(IotAdapter adapter, ReadSnapshots snapshots, ObjectMapper mapper) {
        this.adapter = adapter;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    @GetMapping("/company-devices")
    public ResponseEntity<?> companyDevices(HttpServletRequest request) {
        ResponseEntity<?> source = requireCompanySource(request);
        if (source != null) return source;
        var principal = PrincipalContext.getPrincipalInfo();
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSourceEnum.COMPANY, principal.ownerKey());
        return ResponseEntity.ok(snapshots.read(snapshotIdentity(identity), "COMPANY_DEVICES", Map.of(),
                ReadSnapshots.PolicyEnum.CURRENT, () -> companyDirectory(identity)));
    }

    private Map<String, Object> companyDirectory(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(identity).block(ADAPTER_TIMEOUT);
            if (scope == null || scope.source() != IotSourceEnum.COMPANY || !identity.equals(scope.identity())) {
                throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "读取当前设备目录失败", 503);
            }
            List<Map<String, Object>> devices = scope.devices().stream().map(IotDeviceController::deviceView).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source", "company");
            body.put("observedAt", Instant.now().toString());
            body.put("devices", devices);
            return body;
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new UpstreamUnavailableException("读取当前设备目录");
        }
    }

    @GetMapping({"/devices/candidates", "/tools/find_device_candidates"})
    public ResponseEntity<?> candidates(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestParam String kind,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) String regionId,
            @RequestParam(required = false) String scopeText,
            @RequestParam(required = false, name = "scopeTerms") List<String> scopeTerms,
            @RequestParam(required = false, name = "nameHints") List<String> nameHints,
            @RequestParam(required = false) String deviceText) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException("INVALID_AGENT_TOOL", "conversationId is required for candidate discovery");
        }
        DeviceCandidateQuery query = new DeviceCandidateQuery(kind, List.of(), scopeText,
                scopeTerms == null ? List.of() : scopeTerms,
                nameHints == null ? List.of() : nameHints, deviceText, regionId);
        RequestIdentity identity = identity(source);
        java.util.function.Supplier<Map<String, Object>> load = () -> candidateView(
                adapter.findDeviceCandidates(identity, query, new DeviceCandidateBinding(conversationId)).block(ADAPTER_TIMEOUT));
        if (request.getRequestURI().contains("/tools/")) return ResponseEntity.ok(load.get());
        Map<String, Object> key = mapper.convertValue(query, new TypeReference<>() { });
        key.put("conversationId", conversationId);
        return ResponseEntity.ok(snapshots.read(snapshotIdentity(identity), "DEVICE_CANDIDATES", key,
                ReadSnapshots.PolicyEnum.CURRENT, load));
    }

    @PostMapping({"/devices/states", "/devices/status", "/tools/read_device_states"})
    public ResponseEntity<?> states(
            HttpServletRequest request,
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestParam(required = false) String conversationId,
            @RequestBody Map<String, Object> body) {
        RequestIdentity identity = identity(source);
        if (body != null && body.containsKey("candidateSets")) {
            if (conversationId == null || conversationId.isBlank()) {
                throw new BusinessException("INVALID_AGENT_TOOL", "conversationId is required for candidate-set state reads");
            }
            return ResponseEntity.ok(adapter.readDeviceStates(identity,
                    parseCapabilityRequest(body), conversationId).block(ADAPTER_TIMEOUT));
        }
        List<String> ids = strings(body == null ? null : body.get("deviceIds")).stream().sorted().toList();
        if (request.getRequestURI().contains("/tools/")) {
            return ResponseEntity.ok(adapter.readDeviceStates(identity, ids).block(ADAPTER_TIMEOUT));
        }
        return ResponseEntity.ok(snapshots.read(snapshotIdentity(identity), "DEVICE_STATES", Map.of("deviceIds", ids),
                ReadSnapshots.PolicyEnum.CURRENT, () -> json(adapter.readDeviceStates(identity, ids).block(ADAPTER_TIMEOUT))));
    }

    @PostMapping({"/devices/capabilities", "/tools/inspect_device_capabilities"})
    public ResponseEntity<?> capabilities(
            @RequestParam(required = false, defaultValue = "office") String source,
            @RequestParam(required = false) String conversationId,
            @RequestBody Map<String, Object> body) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException("INVALID_AGENT_TOOL", "conversationId is required for capability inspection");
        }
        DeviceCapabilityRequest request = parseCapabilityRequest(body);
        DeviceCapabilityInspection inspection = adapter.inspectDeviceCapabilities(identity(source), request, conversationId)
                .block(ADAPTER_TIMEOUT);
        return ResponseEntity.ok(inspection);
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

    private static ReadSnapshots.Identity snapshotIdentity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(), identity.source().name(), identity.ownerKey());
    }

    private Map<String, Object> candidateView(DeviceCandidateSet value) {
        if (value == null) throw new UpstreamUnavailableException("设备候选数据为空");
        return value.publicView();
    }

    private Map<String, Object> json(Object value) {
        if (value == null) throw new UpstreamUnavailableException("设备只读数据为空");
        return mapper.convertValue(value, new TypeReference<>() { });
    }

    private RequestIdentity identity(String source) {
        var principal = PrincipalContext.getPrincipalInfo();
        IotSourceEnum iotSource;
        try {
            iotSource = IotSourceEnum.parse(source == null || source.isBlank() ? "office" : source);
        } catch (RuntimeException invalid) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效");
        }
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), iotSource, principal.ownerKey());
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
