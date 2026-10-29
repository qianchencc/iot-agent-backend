package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotGatewayRoutes;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.OfficeDeviceSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Protocol-neutral WebClient adapter. Route paths are supplied by configuration so
 * an unknown customer endpoint cannot be mistaken for a successful device scope.
 */
public final class WebClientIotAdapter implements IotAdapter {

    private final WebClient webClient;
    private final IotGatewayRoutes routes;
    private final Duration callTimeout;
    private final IotCallPolicy callPolicy;

    public WebClientIotAdapter(WebClient webClient, IotGatewayRoutes routes, Duration callTimeout, IotCallPolicy callPolicy) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.callTimeout = requireTimeout(callTimeout);
        this.callPolicy = Objects.requireNonNull(callPolicy, "callPolicy");
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Mono<AuthorizedDeviceScope> request;
        if (identity.source() == IotSource.OFFICE && routes.hasOfficeDirectoryRoutes()) {
            request = loadOfficeScope(identity);
        } else if (identity.source() == IotSource.COMPANY && routes.hasCompanyCatalogRoutes()) {
            request = loadCompanyScope(identity);
        } else if (routes.path(identity.source()) != null) {
            request = loadPaginatedScope(identity);
        } else {
            request = Mono.error(new UpstreamUnavailableException("物联授权设备范围路径未配置"));
        }
        return execute("load authorized " + identity.source().wireValue() + " device scope", request);
    }

    @Override
    public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        if (identity.source() != IotSource.OFFICE) return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备候选仅支持 Office 来源", 404));
        return execute("find authorized Office device candidates", loadAuthorizedDeviceScope(identity).map(scope -> {
            List<AuthorizedDevice> matching = scope.devices().stream()
                    .filter(device -> device.kind().equalsIgnoreCase(query.kind()))
                    .filter(device -> query.deviceIds().isEmpty() || query.deviceIds().contains(device.id()))
                    .filter(device -> matches(device, query))
                    .toList();
            return new DeviceCandidateSet(null, query, matching, true, matching.size());
        }));
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        Objects.requireNonNull(identity, "identity");
        List<String> ids = deviceIds == null ? List.of() : List.copyOf(deviceIds);
        if (identity.source() != IotSource.OFFICE) return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备状态仅支持 Office 来源", 404));
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank()) || ids.size() != ids.stream().distinct().count()) {
            return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效"));
        }
        return execute("read authorized Office device states", loadAuthorizedDeviceScope(identity)
                .flatMap(scope -> readOfficeRecords(identity).map(records -> {
                    Map<String, JsonNode> recordsById = new LinkedHashMap<>();
                    for (JsonNode record : records) {
                        String id = text(record, "id");
                        if (id != null) recordsById.putIfAbsent(id, record);
                    }
                    Map<String, Object> states = new LinkedHashMap<>();
                    for (String id : ids) {
                        AuthorizedDevice authorized = scope.device(id).orElseThrow(() -> new BusinessException("DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403));
                        states.put(id, stateOf(authorized, recordsById.get(id)));
                    }
                    return new DeviceStateReadResult(states, Instant.now().toString());
                })));
    }

    @Override
    public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(request, "request");
        if (identity.source() != IotSource.OFFICE) return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备能力仅支持 Office 来源", 404));
        if (request.candidateSets().isEmpty()) return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集不能为空"));
        return execute("inspect authorized Office device capabilities", loadAuthorizedDeviceScope(identity).map(scope -> {
            List<DeviceCapabilityInspection.DeviceCapability> capabilities = new ArrayList<>();
            for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
                if (selection.deviceIds().isEmpty() || selection.deviceIds().stream().distinct().count() != selection.deviceIds().size()) {
                    throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
                }
                for (String id : selection.deviceIds()) {
                    AuthorizedDevice device = scope.device(id).orElseThrow(() -> new BusinessException("DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403));
                    capabilities.add(capability(device));
                }
            }
            return new DeviceCapabilityInspection(request.candidateSets().stream().map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(), capabilities);
        }));
    }

    private Mono<AuthorizedDeviceScope> loadOfficeScope(RequestIdentity identity) {
        Mono<List<JsonNode>> membership = getJson(routes.officeMembershipPath(), Map.of(), identity)
                .map(body -> contentArray(body, "读取 Office 成员范围"));
        return membership.flatMap(offices -> {
            List<String> officeIds = offices.stream().map(row -> text(row, "elementId", "id")).filter(Objects::nonNull).distinct().toList();
            if (offices.stream().anyMatch(row -> text(row, "elementId", "id") == null)) return Mono.error(new UpstreamUnavailableException("读取 Office 成员范围响应"));
            List<Mono<List<AuthorizedDevice>>> jobs = new ArrayList<>();
            for (OfficeDeviceSource source : routes.officeDeviceSources()) {
                if (source.requiresOfficeContext()) {
                    for (JsonNode office : offices) {
                        String officeId = text(office, "elementId", "id");
                        String officeName = text(office, "elementName", "name");
                        Map<String, String> params = new LinkedHashMap<>(source.params());
                        params.put("elementId", officeId);
                        jobs.add(readSource(source, params, identity, officeName));
                    }
                } else {
                    jobs.add(readSource(source, source.params(), identity, null));
                }
            }
            return Flux.concat(jobs).collectList().map(groups -> mergeScope(identity, groups, officeIds));
        });
    }

    private Mono<List<AuthorizedDevice>> readSource(OfficeDeviceSource source, Map<String, String> params, RequestIdentity identity, String officeName) {
        return page(source.path(), params, identity).map(rows -> rows.stream().map(row -> parseDevice(row, source, officeName)).toList());
    }

    private Mono<AuthorizedDeviceScope> loadCompanyScope(RequestIdentity identity) {
        Mono<List<AuthorizedDevice>> observed = Flux.just(new String[]{"environment", "YQ-ENV-SENSOR"}, new String[]{"energy", "YQ-ENERGY-EQUIPMENT"}, new String[]{"safety", "YQ-SAFETY-OBSERVATION"})
                .concatMap(spec -> page(routes.companyObservationPath(), Map.of("modelCode", spec[1]), identity)
                        .map(rows -> rows.stream().map(row -> parseCompanyObservation(row, spec[0])).toList()))
                .collectList().map(groups -> {
                    List<AuthorizedDevice> all = new ArrayList<>();
                    for (List<AuthorizedDevice> group : groups) all.addAll(group);
                    return all;
                });
        return Mono.zip(observed, collectCompanySmart(identity, "lighting"), collectCompanySmart(identity, "access"),
                collectCompanySmart(identity, "monitor"), collectCompanyAc(identity)).map(tuple -> {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            List<List<AuthorizedDevice>> groups = List.of(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4(), tuple.getT5());
            for (List<AuthorizedDevice> group : groups) {
                for (AuthorizedDevice device : group) {
                    String key = device.kind() + ":" + device.id();
                    AuthorizedDevice prior = unique.putIfAbsent(key, device);
                    if (prior != null && !prior.equals(device)) throw new UpstreamUnavailableException("读取公司授权设备范围响应");
                }
            }
            return (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, List.copyOf(unique.values()));
        });
    }

    private Mono<List<AuthorizedDevice>> collectCompanySmart(RequestIdentity identity, String family) {
        return areaLeaves(routes.companySmartBasePath() + "/" + family + "/myAreaList", identity)
                .flatMapMany(Flux::fromIterable)
                .concatMap(leaf -> page(routes.companySmartBasePath() + "/" + family + "/devicePage",
                                Map.of("areaFullCode", leaf.code()), identity)
                        .map(rows -> rows.stream().map(row -> parseSmart(row, family, leaf)).toList()))
                .collectList().map(groups -> {
                    Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
                    for (List<AuthorizedDevice> group : groups) {
                        for (AuthorizedDevice device : group) unique.putIfAbsent(device.kind() + ":" + device.id(), device);
                    }
                    return List.copyOf(unique.values());
                });
    }


    private Mono<List<AreaLeaf>> areaLeaves(String path, RequestIdentity identity) {
        return areaChildren(path, null, List.of(), identity).collectList();
    }

    private Flux<AreaLeaf> areaChildren(String path, String parent, List<String> names, RequestIdentity identity) {
        Map<String, String> params = parent == null ? Map.of() : Map.of("parentId", parent);
        return getJson(path, params, identity).flatMapMany(body -> Flux.fromIterable(contentArray(body, "读取智慧设备区域")).flatMap(node -> {
            String id = text(node, "id", "elementId");
            if (id == null) return Flux.error(new UpstreamUnavailableException("智慧设备区域格式无效"));
            String name = text(node, "name", "elementName");
            List<String> next = name != null && !"全部".equals(name) ? append(names, name) : names;
            String code = text(node, "fullCode", "areaFullCode");
            return areaChildren(path, id, next, identity).switchIfEmpty(code == null
                    ? Flux.error(new UpstreamUnavailableException("智慧设备区域缺少区域编码")) : Flux.just(new AreaLeaf(code, next)));
        }));
    }

    private Mono<List<AuthorizedDevice>> collectCompanyAc(RequestIdentity identity) {
        Mono<List<AuthorizedDevice>> scoped = areaLeaves(routes.companyAcAreaPath(), identity)
                .flatMapMany(Flux::fromIterable)
                .concatMap(leaf -> page(routes.companyAcDevicePath(), Map.of("areaFullCode", leaf.code()), identity)
                        .map(rows -> rows.stream().map(row -> parseAc(row, leaf)).toList()))
                .collectList().map(groups -> {
                    List<AuthorizedDevice> all = new ArrayList<>();
                    for (List<AuthorizedDevice> group : groups) all.addAll(group);
                    return all;
                });
        return scoped.zipWith(page(routes.companyAcBindingPath(), Map.of("modelCode", "YQ_AIR_CONDITION"), identity))
                .map(tuple -> {
                    Map<String, JsonNode> bindings = new LinkedHashMap<>();
                    for (JsonNode row : tuple.getT2()) {
                        String id = text(row, "id", "hubDeviceId");
                        if (id != null) bindings.put(id, row);
                    }
                    return tuple.getT1().stream().map(device -> {
                        JsonNode binding = bindings.get(device.hubDeviceId());
                        String control = binding == null ? null : text(binding, "controlRef");
                        if (control == null && binding != null) control = actionId(binding, "airCondition");
                        boolean validBinding = binding != null && control != null && hasAction(binding, "airCondition")
                                && hasAction(binding, "acDetail");
                        return new AuthorizedDevice(device.id(), device.kind(), device.name(), device.installAddress(),
                                !validBinding, validBinding ? control : null, device.officeDeviceRef(), device.hubDeviceId(),
                                device.smartAreaCode(), device.locationPaths());
                    }).toList();
                });
    }

    private static boolean hasAction(JsonNode binding, String type) {
        JsonNode actions = binding.get("actionList");
        if (actions == null || !actions.isArray()) return false;
        for (JsonNode action : actions) if (type.equals(text(action, "actionType"))) return true;
        return false;
    }
    private static String actionId(JsonNode binding, String type) {
        JsonNode actions = binding.get("actionList");
        if (actions != null && actions.isArray()) for (JsonNode action : actions)
            if (type.equals(text(action, "actionType"))) return text(action, "id");
        return null;
    }

    private AuthorizedDevice parseCompanyObservation(JsonNode row, String kind) {
        String id = text(row, "id", "deviceId", "hubDeviceId"), name = text(row, "name", "deviceName");
        if (id == null || name == null) throw new UpstreamUnavailableException("读取公司观察设备响应");
        return new AuthorizedDevice(id, kind, name, text(row, "installAddress", "installAddr"), true, null, id,
                text(row, "hubDeviceId", "id"), null, List.of());
    }

    private AuthorizedDevice parseSmart(JsonNode row, String family, AreaLeaf leaf) {
        String id = text(row, "id", "officeDeviceRef"), hub = text(row, "hubDeviceId");
        if (id == null || hub == null) throw new UpstreamUnavailableException("智慧设备缺少控制绑定");
        String kind = "monitor".equals(family) ? "video" : family;
        String name = text(row, "name", "deviceName");
        return new AuthorizedDevice(id, kind, name == null ? kind : name, text(row, "installAddress", "installAddr"),
                "video".equals(kind), "video".equals(kind) ? null : id, id, hub, leaf.code(), List.of(leaf.path()));
    }

    private AuthorizedDevice parseAc(JsonNode row, AreaLeaf leaf) {
        String hub = text(row, "hubDeviceId", "id");
        if (hub == null) throw new UpstreamUnavailableException("空调设备缺少控制绑定");
        String name = text(row, "name", "deviceName");
        return new AuthorizedDevice(hub, "ac", name == null ? "空调" : name, text(row, "installAddress", "installAddr"),
                true, null, hub, hub, leaf.code(), List.of(leaf.path()));
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values); result.add(value); return result;
    }

    private record AreaLeaf(String code, List<String> path) { }

    private Mono<AuthorizedDeviceScope> loadPaginatedScope(RequestIdentity identity) {
        return page(routes.path(identity.source()), Map.of(), identity).map(records -> {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (JsonNode record : records) {
                AuthorizedDevice device = parseDevice(record, null, null);
                AuthorizedDevice previous = unique.putIfAbsent(device.kind() + ":" + device.id(), device);
                if (previous != null && !previous.equals(device)) {
                    throw new UpstreamUnavailableException("读取物联授权设备范围响应");
                }
            }
            return (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, new ArrayList<>(unique.values()));
        });
    }

    private Mono<List<JsonNode>> readOfficeRecords(RequestIdentity identity) {
        return Flux.fromIterable(routes.officeDeviceSources())
                .concatMap(source -> {
                    if (source.requiresOfficeContext()) {
                        return getJson(routes.officeMembershipPath(), Map.of(), identity)
                                .map(body -> contentArray(body, "读取 Office 成员范围"))
                                .flatMapMany(offices -> Flux.fromIterable(offices).concatMap(office -> {
                                    String officeId = text(office, "elementId", "id");
                                    if (officeId == null) return Flux.error(new UpstreamUnavailableException("读取 Office 成员范围响应"));
                                    Map<String, String> params = new LinkedHashMap<>(source.params());
                                    params.put("elementId", officeId);
                                    return page(source.path(), params, identity);
                                }));
                    }
                    return page(source.path(), source.params(), identity).flux();
                })
                .collectList()
                .map(groups -> groups.stream().flatMap(List::stream).toList());
    }

    private AuthorizedDeviceScope mergeScope(RequestIdentity identity, List<List<AuthorizedDevice>> groups, List<String> officeIds) {
        Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
        int scopedSourceCount = (int) routes.officeDeviceSources().stream().filter(OfficeDeviceSource::requiresOfficeContext).count();
        int scopedGroupCount = officeIds.size() * scopedSourceCount;
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            if (groupIndex >= scopedGroupCount) continue;
            for (AuthorizedDevice candidate : groups.get(groupIndex)) {
                String key = candidate.kind() + ":" + candidate.id();
                AuthorizedDevice previous = unique.putIfAbsent(key, candidate);
                if (previous != null && (!previous.name().equals(candidate.name()) || !previous.installAddress().equals(candidate.installAddress()))) {
                    throw new UpstreamUnavailableException("读取 Office 授权设备范围响应");
                }
            }
        }
        return new AuthorizedDeviceScopeSnapshot(identity, List.copyOf(unique.values()));
    }

    private Mono<List<JsonNode>> page(String path, Map<String, String> params, RequestIdentity identity) {
        return Flux.range(0, 1000).concatMap(page -> getJson(path, withPage(params, page), identity).map(body -> {
            if (body.isArray()) return new PageResult(toList(body), true);
            if (body.path("code").asInt() == 204) return PageResult.empty();
            JsonNode content = body.get("content");
            if (content == null || content.isNull()) throw new UpstreamUnavailableException("读取授权设备目录响应");
            if (content.isArray()) return new PageResult(toList(content), true);
            JsonNode rows = content.get("content");
            if (rows == null || !rows.isArray()) throw new UpstreamUnavailableException("读取授权设备目录响应");
            boolean last = content.path("last").isBoolean() && content.path("last").asBoolean();
            return new PageResult(toList(rows), last || !content.has("last"));
        })).takeUntil(PageResult::last).collectList().flatMap(pages -> {
            if (pages.isEmpty() || !pages.get(pages.size() - 1).last()) {
                return Mono.error(new UpstreamUnavailableException("读取授权设备目录分页未完成"));
            }
            return Mono.just(pages.stream().flatMap(result -> result.records().stream()).toList());
        });
    }

    private Mono<JsonNode> getJson(String path, Map<String, String> params, RequestIdentity identity) {
        return webClient.get().uri(builder -> {
            var uri = builder.path(path);
            params.forEach(uri::queryParam);
            return uri.build();
        }).headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> decodeJson(response, "读取物联只读目录"));
    }

    private Mono<JsonNode> decodeJson(ClientResponse response, String operation) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        if (!status.is2xxSuccessful()) return response.releaseBody().then(Mono.error(new UpstreamUnavailableException(operation)));
        return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(new UpstreamUnavailableException(operation))).flatMap(body -> {
            if (body == null || (!body.isObject() && !body.isArray())) return Mono.error(new UpstreamUnavailableException(operation + "响应"));
            if (body.isArray()) return Mono.just(body);
            if (!body.path("code").isNumber()) return Mono.error(new UpstreamUnavailableException(operation + "响应"));
            int code = body.path("code").asInt();
            if (code == 401 || code == 403) return Mono.error(new GatewayAuthorizationException(code));
            if (code != 0 && code != 200 && code != 204) return Mono.error(new UpstreamUnavailableException(operation));
            return Mono.just(body);
        });
    }

    private Mono<AuthorizedDeviceScope> decode(ClientResponse response, RequestIdentity identity) {
        return decodeJson(response, "读取物联授权设备范围").flatMap(body -> parseBody(body, identity));
    }

    private Mono<AuthorizedDeviceScope> parseBody(JsonNode root, RequestIdentity identity) {
        JsonNode content = root.get("content");
        if (content != null && content.isObject()) content = content.get("content");
        if (content == null || !content.isArray()) return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        try {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (JsonNode record : content) {
                AuthorizedDevice device = parseDevice(record, null, null);
                AuthorizedDevice prior = unique.putIfAbsent(device.kind() + ":" + device.id(), device);
                if (prior != null && !prior.equals(device)) return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
            }
            return Mono.just(new AuthorizedDeviceScopeSnapshot(identity, new ArrayList<>(unique.values())));
        } catch (IllegalArgumentException malformed) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        }
    }

    private AuthorizedDevice parseDevice(JsonNode record, OfficeDeviceSource source, String officeName) {
        if (record == null || !record.isObject()) throw new IllegalArgumentException("device record is not an object");
        String id = text(record, "id", "deviceId");
        String kind = source == null ? text(record, "kind", "deviceKind") : source.kind();
        String name = text(record, "name", "deviceName");
        String address = text(record, "installAddress", "installAddr");
        if (id == null || kind == null || name == null) throw new IllegalArgumentException("device identity is incomplete");
        if (address == null) address = officeName == null ? "" : officeName;
        boolean observationOnly = "video".equalsIgnoreCase(kind) || record.path("observationOnly").asBoolean(false);
        return new AuthorizedDevice(id, kind, name, address, observationOnly,
                text(record, "controlRef"), text(record, "officeDeviceRef", "id"), text(record, "hubDeviceId"),
                text(record, "smartAreaCode"), List.of());
    }

    private DeviceCapabilityInspection.DeviceCapability capability(AuthorizedDevice device) {
        List<Map<String, Object>> commands = new ArrayList<>();
        if ("lighting".equalsIgnoreCase(device.kind())) {
            commands.add(Map.<String, Object>of("closeOrOpen", 0));
            commands.add(Map.<String, Object>of("closeOrOpen", 1));
        } else if ("access".equalsIgnoreCase(device.kind())) {
            commands.add(Map.<String, Object>of("controlType", 2));
        }
        DeviceCapabilityInspection.MapParameters params = "ac".equalsIgnoreCase(device.kind()) && !device.observationOnly()
                ? new DeviceCapabilityInspection.MapParameters(16, 30, 1, List.of("auto", "cool", "heat", "dry", "fan"), List.of("low", "high", "auto")) : null;
        return new DeviceCapabilityInspection.DeviceCapability(device.id(), device.kind(), commands, params);
    }

    private Object stateOf(AuthorizedDevice device, JsonNode record) {
        if (record == null || device.observationOnly() && !"video".equalsIgnoreCase(device.kind())) return null;
        return switch (device.kind().toLowerCase()) {
            case "lighting" -> record.has("switchStatus") && !record.get("switchStatus").isNull() ? record.get("switchStatus").asBoolean() : null;
            case "video" -> record.has("netStatus") ? "ON_LINE".equals(record.get("netStatus").asText()) ? Boolean.TRUE : "OFF_LINE".equals(record.get("netStatus").asText()) ? Boolean.FALSE : null : null;
            case "ac" -> {
                Map<String, Object> state = new LinkedHashMap<>();
                if (record.has("switchStatus") && !record.get("switchStatus").isNull()) state.put("power", record.get("switchStatus").asBoolean() ? "on" : "off");
                if (record.has("temperature") && !record.get("temperature").isNull()) state.put("temperature", record.get("temperature").numberValue());
                if (record.has("targetTemperature") && !record.get("targetTemperature").isNull()) state.put("targetTemperature", record.get("targetTemperature").numberValue());
                yield state.isEmpty() ? null : state;
            }
            default -> null;
        };
    }

    private static boolean matches(AuthorizedDevice device, DeviceCandidateQuery query) {
        String haystack = (device.name() + " " + device.installAddress()).toLowerCase();
        if (query.scopeText() != null && !query.scopeText().isBlank() && !haystack.contains(query.scopeText().toLowerCase())) return false;
        for (String term : query.scopeTerms()) if (!term.isBlank() && !haystack.contains(term.toLowerCase())) return false;
        if (query.deviceText() != null && !query.deviceText().isBlank() && !haystack.contains(query.deviceText().toLowerCase())) return false;
        return true;
    }

    private static Map<String, String> withPage(Map<String, String> params, int page) {
        Map<String, String> all = new LinkedHashMap<>(params);
        all.put("page", Integer.toString(page));
        all.put("size", "100");
        return all;
    }

    private static List<JsonNode> contentArray(JsonNode body, String operation) {
        if (body.isArray()) return toList(body);
        JsonNode content = body.get("content");
        if (content != null && content.isObject()) content = content.get("content");
        if (body.path("code").asInt() == 204) return List.of();
        if (content == null || !content.isArray()) throw new UpstreamUnavailableException(operation + "响应");
        return toList(content);
    }

    private static List<JsonNode> toList(JsonNode rows) {
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(result::add);
        return result;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private <T> Mono<T> execute(String operation, Mono<T> request) {
        return callPolicy.execute(operation, request).timeout(callTimeout)
                .onErrorMap(error -> error instanceof TimeoutException, error -> new UpstreamUnavailableException(operation + "超时"))
                .onErrorMap(error -> !(error instanceof BusinessException) && !(error instanceof CancellationException), error -> new UpstreamUnavailableException(operation + "失败"));
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("callTimeout must be positive");
        return timeout;
    }

    private record PageResult(List<JsonNode> records, boolean last) {
        static PageResult empty() { return new PageResult(List.of(), true); }
    }
}
