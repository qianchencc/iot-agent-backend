package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.AcCommandCapabilities;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotGatewayRoutes;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.OfficeDeviceSource;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import ltd.qianc.iotagent.iot.VideoPlaybackPreparation;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.iot.VideoStream;
import ltd.qianc.iotagent.iot.VideoStreamBusyException;
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

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long READ_SCOPE_REUSE_MILLIS = 5_000L;
    private static final long VIDEO_SCOPE_REUSE_MILLIS = 60_000L;
    /**
     * Runtime worker queue bound: coarse lighting-area reads and AC detail reads
     * share one queue per call (originally three lanes, user-approved raise to 6).
     * After a failure no new job is claimed; already-claimed reads drain before the
     * first failure is rethrown. This is a per-call bound, not a process-wide limit.
     */
    private static final int READ_QUEUE_CONCURRENCY = 6;
    /**
     * Runtime directory fan-out bound: Office source reads, Company smart/AC leaf
     * pages and area-tree siblings were unbounded Promise.all fan-outs in the Node
     * runtime; the migration caps them at 6 per call. Fail-fast like Promise.all.
     */
    private static final int DIRECTORY_FANOUT_CONCURRENCY = 6;
    /**
     * Office kinds whose scoped office pages carry current state: lighting's
     * switchStatus boolean, AC's coarse switch/temperature fields and video's
     * netStatus. Access has no state semantics (runtime keeps it unknown), so its
     * pages and the account-wide association joins are never state sources.
     */
    private static final Set<String> STATE_SOURCE_KINDS = Set.of("lighting", "video", "ac");
    /**
     * The tool bridge blocks state reads for 30s. Keep a business-owned deadline
     * below it so a slow read becomes an explicit 503 instead of an unclassified
     * blocking-get IllegalStateException that looks like an unknown failure.
     */
    private static final Duration STATE_READ_DEADLINE = Duration.ofSeconds(25);
    private final WebClient webClient;
    private final IotGatewayRoutes routes;
    private final Duration callTimeout;
    private final Duration compositionTimeout;
    private final IotCallPolicy callPolicy;
    private final DeviceCandidateSetStore candidateStore;
    private final ConcurrentMap<String, ScopeEntry> scopeCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Mono<AuthorizedDeviceScope>> scopeJobs = new ConcurrentHashMap<>();
    public WebClientIotAdapter(WebClient webClient, IotGatewayRoutes routes, Duration callTimeout, IotCallPolicy callPolicy) {
        this(webClient, routes, callTimeout, callPolicy, null);
    }
    public WebClientIotAdapter(WebClient webClient, IotGatewayRoutes routes, Duration callTimeout,
                               IotCallPolicy callPolicy, DeviceCandidateSetStore candidateStore) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.callTimeout = requireTimeout(callTimeout);
        this.compositionTimeout = this.callTimeout.compareTo(Duration.ofSeconds(120)) > 0
                ? this.callTimeout : Duration.ofSeconds(120);
        this.callPolicy = Objects.requireNonNull(callPolicy, "callPolicy");
        this.candidateStore = candidateStore;
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Mono<AuthorizedDeviceScope> request;
        if (identity.source() == IotSourceEnum.OFFICE && routes.hasOfficeDirectoryRoutes()) {
            request = loadOfficeScope(identity);
        } else if (identity.source() == IotSourceEnum.COMPANY && routes.hasCompanyCatalogRoutes()) {
            request = loadCompanyScope(identity);
        } else if (routes.path(identity.source()) != null) {
            request = loadPaginatedScope(identity);
        } else {
            request = Mono.error(new UpstreamUnavailableException("物联授权设备范围路径未配置"));
        }
        return execute("load authorized " + identity.source().wireValue() + " device scope",
                cachedReadScope(identity, request));
    }

    private Mono<AuthorizedDeviceScope> cachedReadScope(
            RequestIdentity identity, Mono<AuthorizedDeviceScope> request) {
        String key = identity.tenantCode() + "\u0000" + identity.ownerKey() + "\u0000" + identity.source();
        // Control authorization stays fresh. Video and Office reads reuse only complete scopes.
        return cachedReadScope(key, request, identity.source() == IotSourceEnum.OFFICE ? READ_SCOPE_REUSE_MILLIS : 0);
    }

    private Mono<AuthorizedDeviceScope> cachedReadScope(String key, Mono<AuthorizedDeviceScope> request, long reuseMillis) {
        long now = System.currentTimeMillis();
        scopeCache.forEach((cacheKey, entry) -> {
            if (entry.expiresAt() <= now) scopeCache.remove(cacheKey, entry);
        });
        ScopeEntry cached = scopeCache.get(key);
        if (cached != null && cached.expiresAt() > now) return Mono.just(cached.scope());
        Mono<AuthorizedDeviceScope> fresh = request
                .doOnNext(scope -> {
                    if (reuseMillis > 0) scopeCache.put(key, new ScopeEntry(scope, System.currentTimeMillis() + reuseMillis));
                })
                .doFinally(signal -> scopeJobs.remove(key))
                .cache();
        Mono<AuthorizedDeviceScope> prior = scopeJobs.putIfAbsent(key, fresh);
        return prior == null ? fresh : prior;
    }

    @Override
    public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query) {
        return findDeviceCandidates(identity, query, null);
    }

    @Override
    public Mono<DeviceCandidateSet> findDeviceCandidates(
            RequestIdentity identity, DeviceCandidateQuery query, DeviceCandidateBinding binding) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(query, "query");
        if (identity.source() != IotSourceEnum.OFFICE && identity.source() != IotSourceEnum.COMPANY) {
            return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备候选仅支持 Office 或 Company 来源", 404));
        }
        if (binding != null && candidateStore == null) {
            return Mono.error(new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503));
        }
        return execute("find authorized " + identity.source().wireValue() + " device candidates",
                scopedForRegion(identity, query.regionId()).map(scope -> {
                    DeviceCandidateMatcher.Result result = DeviceCandidateMatcher.search(scope.devices(), query);
                    String setId = UUID.randomUUID().toString();
                    DeviceCandidateSet set = new DeviceCandidateSet(setId, query, result.visible(),
                            result.complete(), result.totalMatches(), result.groups(),
                            result.nameHintMatches(), result.unmatchedNameHints(), result.resolution(), result.members());
                    if (binding != null) {
                        DeviceCandidateRecord saved = candidateStore.save(new DeviceCandidateRecord(setId, binding.conversationId(), identity.tenantCode(), identity.ownerKey(), identity.source(), scope.catalogFingerprint(), query, result.members(), result.complete(), result.totalMatches(), Instant.now().toString(), result.resolution().mode(), null));
                        // Runtime persistCandidateSet: the persisted record owns the sN handle; the
                        // model-facing set carries device handles parallel to its visible devices.
                        if (saved.handle() != null && !saved.handle().isBlank()) {
                            List<String> deviceHandles = new ArrayList<>();
                            for (int index = 0; index < result.visible().size(); index++) {
                                deviceHandles.add(saved.deviceHandle(index));
                            }
                            set = new DeviceCandidateSet(setId, query, result.visible(), result.complete(),
                                    result.totalMatches(), result.groups(), result.nameHintMatches(),
                                    result.unmatchedNameHints(), result.resolution(), saved.handle(), deviceHandles, result.members());
                        }
                    }
                    return set;
                }));
    }

    private Mono<AuthorizedDeviceScope> scopedForRegion(RequestIdentity identity, String regionId) {
        Mono<AuthorizedDeviceScope> scope = loadAuthorizedDeviceScope(identity);
        if (identity.source() != IotSourceEnum.OFFICE || regionId == null || regionId.isBlank()) return scope;
        return scope.flatMap(current -> loadOfficeRegions(identity).flatMap(regions -> {
            if (regions.stream().noneMatch(region -> region.id().equals(regionId))) {
                return Mono.error(new BusinessException("FOCUS_REGION_UNAVAILABLE", "当前关注区域不在用户授权范围内", 403));
            }
            return Mono.just(current.retain(device -> device.officeIds().contains(regionId)));
        }));
    }

    @Override
    public Mono<List<OfficeRegion>> loadOfficeRegions(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (identity.source() != IotSourceEnum.OFFICE || routes.officeMembershipPath() == null) {
            return Mono.error(new BusinessException("OFFICE_REGION_UNAVAILABLE", "Office 区域目录未配置", 404));
        }
        return execute("load Office membership regions", readOfficeMembers(identity)
                .map(offices -> offices.stream()
                        .map(office -> new OfficeRegion(text(office, "elementId", "id"),
                                text(office, "elementName", "name")))
                        .toList()));
    }

    private Mono<List<JsonNode>> readOfficeMembers(RequestIdentity identity) {
        return getJson(routes.officeMembershipPath(), Map.of(), identity)
                .map(body -> contentArray(body, "读取 Office 成员范围"))
                .flatMap(offices -> offices.stream().anyMatch(row -> text(row, "elementId", "id") == null)
                        ? Mono.error(new UpstreamUnavailableException("读取 Office 成员范围响应"))
                        : Mono.just(offices));
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(
            RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(request, "request");
        if (conversationId == null || conversationId.isBlank()) {
            return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集会话绑定缺失"));
        }
        if (candidateStore == null) {
            return Mono.error(new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503));
        }
        validateCapabilitySelections(request);
        if (identity.source() == IotSourceEnum.COMPANY) {
            return stateReadDeadline(execute("read authorized state for candidate sets",
                    resolveCompanyCandidateDevices(identity, request, conversationId)
                            .flatMap(devices -> readAuthorizedCompanyStates(identity, devices))));
        }
        return execute("read authorized state for candidate sets", loadAuthorizedDeviceScope(identity).map(scope -> {
            Set<String> ids = new java.util.LinkedHashSet<>();
            for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
                DeviceCandidateRecord set = requiredCandidateSet(identity, selection.candidateSetId(), conversationId);
                Map<String, AuthorizedDevice> members = membersById(set);
                AuthorizedDeviceScope setScope = candidateScope(scope, set);
                if (!setScope.catalogFingerprint().equals(set.catalogFingerprint())) {
                    throw new BusinessException("CANDIDATE_SET_STALE", "设备候选集已过期，请重新检索", 409);
                }
                for (String id : selection.deviceIds()) {
                    if (!members.containsKey(id) || setScope.device(id).isEmpty()) {
                        throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备不属于引用的候选集", 403);
                    }
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        })).flatMap(ids -> readDeviceStates(identity, ids));
    }

    @Override
    public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
        Objects.requireNonNull(identity, "identity");
        List<String> ids = deviceIds == null ? List.of() : List.copyOf(deviceIds);
        if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank()) || ids.size() != ids.stream().distinct().count()) {
            return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效"));
        }
        if (identity.source() == IotSourceEnum.COMPANY) return readCompanyDeviceStates(identity, ids);
        if (identity.source() != IotSourceEnum.OFFICE) return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备状态仅支持 Office 来源", 404));
        return execute("read authorized Office device states", loadAuthorizedDeviceScope(identity)
                .flatMap(scope -> {
                    // Runtime readStateObservations resolves every requested id against
                    // the catalog snapshot before any state read; an out-of-scope device
                    // must not cost additional state network calls.
                    Map<String, AuthorizedDevice> requested = new LinkedHashMap<>();
                    for (String id : ids) {
                        requested.put(id, scope.device(id).orElseThrow(() ->
                                new BusinessException("DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403)));
                    }
                    return readOfficeStateRecords(identity, requested.values()).map(recordsById -> {
                        Map<String, Object> states = new LinkedHashMap<>();
                        requested.forEach((id, device) -> states.put(id, stateOf(device, recordsById.get(id))));
                        return new DeviceStateReadResult(states, Instant.now().toString());
                    });
                }));
    }
    @Override
    public Mono<DeviceStateReadResult> readObservationStates(
            RequestIdentity identity, List<AuthorizedDevice> devices) {
        Objects.requireNonNull(identity, "identity");
        if (identity.source() != IotSourceEnum.COMPANY) {
            return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "观察状态仅支持 Company 来源", 404));
        }
        List<AuthorizedDevice> requested = (devices == null ? List.<AuthorizedDevice>of() : devices).stream()
                .filter(device -> device != null
                        && ("environment".equalsIgnoreCase(device.kind())
                        || "energy".equalsIgnoreCase(device.kind())))
                .distinct().toList();
        List<AuthorizedDevice> energyDevices = requested.stream()
                .filter(device -> "energy".equalsIgnoreCase(device.kind())).toList();
        Mono<Map<String, Object>> energy = energyDevices.isEmpty() ? Mono.just(Map.of())
                : energyStates(identity, energyDevices)
                        .onErrorResume(error -> error instanceof GatewayAuthorizationException
                                ? Mono.error(error) : Mono.just(Map.of()));
        // The Node runtime looped environment devices serially and only then read the
        // energy batch (adapter.ts readCompanyObservationStates). Those reads have no
        // ordering dependency, so this round's bound puts them on one shared six-lane
        // queue together with the energy batch instead of keeping the serial phase.
        // Result order stays device order with the energy entries last; an
        // authorization failure keeps the stop-claiming drain shape.
        List<Mono<Map<String, Object>>> reads = new ArrayList<>();
        for (AuthorizedDevice device : requested) {
            if (!"environment".equalsIgnoreCase(device.kind())) continue;
            reads.add(environmentState(identity, device).map(state -> {
                Map<String, Object> entryState = new LinkedHashMap<>();
                entryState.put(device.id(), state);
                return entryState;
            }));
        }
        reads.add(energy);
        return drainQueue(reads).map(states -> {
            for (AuthorizedDevice device : requested) states.putIfAbsent(device.id(), null);
            return new DeviceStateReadResult(states, Instant.now().toString());
        });
    }

    private Mono<DeviceStateReadResult> readCompanyDeviceStates(RequestIdentity identity, List<String> ids) {
        return stateReadDeadline(execute("read authorized Company device states", loadCompanyScope(identity)
                .flatMap(scope -> {
                    List<AuthorizedDevice> requested = ids.stream()
                            .map(id -> scope.device(id).orElseThrow(() -> new BusinessException(
                                    "DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403)))
                            .toList();
                    return readAuthorizedCompanyStates(identity, requested);
                })));
    }

    private static Mono<DeviceStateReadResult> stateReadDeadline(Mono<DeviceStateReadResult> read) {
        return read.timeout(STATE_READ_DEADLINE)
                .onErrorMap(TimeoutException.class, timeout ->
                        new UpstreamUnavailableException("读取设备当前状态"));
    }

    /**
     * Reads states for already-authorized Company devices without rebuilding the
     * seven-family catalog. The caller has just revalidated each device against its
     * own family catalog; unrelated families must not dominate the read timeout.
     */
    private Mono<DeviceStateReadResult> readAuthorizedCompanyStates(
            RequestIdentity identity, List<AuthorizedDevice> requested) {
        List<AuthorizedDevice> observed = requested.stream()
                .filter(device -> "environment".equalsIgnoreCase(device.kind())
                        || "energy".equalsIgnoreCase(device.kind()))
                .toList();
        Mono<Map<String, Object>> observationStates = observed.isEmpty()
                ? Mono.just(Map.of())
                : readObservationStates(identity, observed).map(DeviceStateReadResult::states);
        // Runtime groups coarse lighting/video reads by kind + area, not by device.
        // One batch must use one area observation; the coarse area reads and AC
        // detail reads share one bounded worker queue.
        record SmartArea(String kind, String code) { }
        Map<SmartArea, List<AuthorizedDevice>> areas = new LinkedHashMap<>();
        List<Mono<Map<String, Object>>> reads = new ArrayList<>();
        for (AuthorizedDevice device : requested) {
            if ("lighting".equals(device.kind()) || "video".equals(device.kind())) {
                if (device.smartAreaCode() != null && !device.smartAreaCode().isBlank()) {
                    areas.computeIfAbsent(new SmartArea(device.kind(), device.smartAreaCode()),
                            ignored -> new ArrayList<>()).add(device);
                }
            } else if ("ac".equals(device.kind())) {
                reads.add(companyAcState(identity, device).map(state -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put(device.id(), state);
                    return result;
                }));
            }
        }
        areas.forEach((area, devices) -> {
            String family = "video".equals(area.kind()) ? "monitor" : area.kind();
            reads.add(page(routes.companySmartBasePath() + "/" + family + "/devicePage",
                    Map.of("areaFullCode", area.code()), identity).map(rows -> {
                Map<String, JsonNode> byId = new LinkedHashMap<>();
                for (JsonNode row : rows) {
                    String id = text(row, "id", "officeDeviceRef");
                    if (id != null) byId.putIfAbsent(id, row);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                for (AuthorizedDevice device : devices) {
                    result.put(device.id(), companySmartState(device, byId.get(device.officeDeviceRef())));
                }
                return result;
            }));
        });
        // Runtime worker queue (adapter.ts readCompanyDeviceStates): observation
        // states are awaited first, then coarse area reads and AC reads share one
        // bounded drain queue.
        Mono<Map<String, Object>> smartStates = drainQueue(reads);
        return observationStates.flatMap(observedStates -> smartStates.map(queueStates -> {
            Map<String, Object> states = new LinkedHashMap<>(observedStates);
            states.putAll(queueStates);
            for (AuthorizedDevice device : requested) states.putIfAbsent(device.id(), null);
            String observedAt = Instant.now().toString();
            return new DeviceStateReadResult(states, observedAt, companyObservationBlocks(requested, states));
        }));
    }

    /**
     * One bounded read queue: record the first failure, stop claiming new work,
     * drain claimed reads, then rethrow. Callers retain their phase ordering, and
     * merged chunks keep the first record per key in queue order (flatMapSequential
     * preserves emission order), matching the old serial sweep's putIfAbsent
     * precedence; Company callers emit disjoint keys either way.
     */
    private static Mono<Map<String, Object>> drainQueue(List<Mono<Map<String, Object>>> reads) {
        return Mono.defer(() -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return Flux.fromIterable(reads)
                    .flatMapSequential(read -> failure.get() == null
                            ? read.onErrorResume(error -> {
                                failure.compareAndSet(null, error);
                                return Mono.empty();
                            })
                            : Mono.empty(), READ_QUEUE_CONCURRENCY)
                    .collect(() -> new LinkedHashMap<String, Object>(), (merged, chunk) -> chunk.forEach(merged::putIfAbsent))
                    .flatMap(collected -> failure.get() == null
                            ? Mono.just(collected)
                            : Mono.error(failure.get()));
        });
    }

    private Mono<List<AuthorizedDevice>> resolveCompanyCandidateDevices(
            RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        validateCapabilitySelections(request);
        Map<String, AuthorizedDevice> stored = new LinkedHashMap<>();
        for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
            DeviceCandidateRecord set = requiredCandidateSet(identity, selection.candidateSetId(), conversationId);
            Map<String, AuthorizedDevice> members = membersById(set);
            for (String id : selection.deviceIds()) {
                AuthorizedDevice member = members.get(id);
                if (member == null) throw new BusinessException(
                        "INVALID_DEVICE_REFERENCE", "设备不属于引用的候选集", 403);
                stored.put(id, member);
            }
        }
        Set<String> kinds = stored.values().stream().map(AuthorizedDevice::kind)
                .collect(java.util.stream.Collectors.toSet());
        return loadCompanyScopeForKinds(identity, kinds).map(scope -> {
            List<AuthorizedDevice> current = new ArrayList<>();
            for (String id : stored.keySet()) {
                AuthorizedDevice member = stored.get(id);
                AuthorizedDevice device = scope.device(id)
                        .filter(candidate -> candidate.kind().equals(member.kind()))
                        .orElseThrow(() -> new BusinessException(
                                "CANDIDATE_SET_STALE", "设备候选集已过期，请重新检索", 409));
                if (!device.catalogFingerprint().equals(member.catalogFingerprint())) {
                    throw new BusinessException("CANDIDATE_SET_STALE", "设备候选集已过期，请重新检索", 409);
                }
                current.add(device);
            }
            return List.copyOf(current);
        });
    }

    private static List<Map<String, Object>> companyObservationBlocks(
            List<AuthorizedDevice> requested, Map<String, Object> states) {
        List<Map<String, Object>> videos = requested.stream()
                .filter(device -> "video".equalsIgnoreCase(device.kind()))
                .map(device -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("deviceId", device.id());
                    row.put("name", device.name());
                    row.put("location", device.installAddress() == null || device.installAddress().isBlank()
                            ? device.name() : device.installAddress());
                    row.put("online", states.get(device.id()));
                    return row;
                })
                .toList();
        return videos.isEmpty() ? List.of() : List.of(Map.of("type", "video", "devices", videos));
    }

    private static Object companySmartState(AuthorizedDevice device, JsonNode row) {
        if (row == null) return null;
        if ("lighting".equals(device.kind())) {
            return row.has("switchStatus") && !row.get("switchStatus").isNull() ? row.get("switchStatus").asBoolean() : null;
        }
        String status = text(row, "netStatus");
        return "ON_LINE".equalsIgnoreCase(status) ? Boolean.TRUE : "OFF_LINE".equalsIgnoreCase(status) ? Boolean.FALSE : null;
    }

    private Mono<Object> companyAcState(RequestIdentity identity, AuthorizedDevice device) {
        String action = metadataText(device, "companyAcDetailActionId");
        if (device.hubDeviceId() == null || action == null) return Mono.justOrEmpty((Object) null);
        return webClient.post().uri("/micro-api/api/iot-center/interactive/{hub}/{action}/down", device.hubDeviceId(), action)
                .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .bodyValue(Map.of())
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 401 || status == 403) {
                        return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status)));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(new UpstreamUnavailableException("读取空调回读响应")));
                    }
                    return response.bodyToMono(JsonNode.class)
                            .switchIfEmpty(Mono.error(new UpstreamUnavailableException("读取空调回读响应")))
                            .flatMap(body -> {
                                int code = body.path("code").asInt(0);
                                if (code == 401 || code == 403) return Mono.error(new GatewayAuthorizationException(code));
                                JsonNode content = body.get("content");
                                if (content != null && content.isTextual()) {
                                    try { content = JSON.readTree(content.asText()); } catch (Exception ignored) { content = null; }
                                }
                                int nested = content == null ? 0 : content.path("code").asInt(0);
                                if (nested == 401 || nested == 403) return Mono.error(new GatewayAuthorizationException(nested));
                                return Mono.justOrEmpty(companyAcState(content));
                            });
                })
                .timeout(callTimeout)
                .onErrorResume(error -> error instanceof GatewayAuthorizationException
                        ? Mono.error(error) : Mono.empty());
    }

    private static Object companyAcState(JsonNode content) {
        if (content == null) return null;
        if (content.isTextual()) {
            try { content = JSON.readTree(content.asText()); } catch (Exception ignored) { return null; }
        }
        JsonNode data = content.path("data");
        if (!data.isObject()) return null;
        Map<String, Object> state = new LinkedHashMap<>();
        if (data.path("O").has("val")) state.put("power", data.path("O").path("val").asInt() == 1 ? "on" : "off");
        if (data.path("tT").has("val")) state.put("temperature", data.path("tT").path("val").asDouble() / 10.0);
        if (data.path("MODE").has("val")) state.put("mode", Map.of(2, "fan", 3, "cool", 4, "heat", 5, "dry", 7, "auto").get(data.path("MODE").path("val").asInt()));
        if (data.path("F").has("val")) state.put("fanSpeed", Map.of(15, "low", 75, "high", 101, "auto").get(data.path("F").path("val").asInt()));
        return state.isEmpty() ? null : state;
    }

    private Mono<Object> environmentState(RequestIdentity identity, AuthorizedDevice device) {
        return environmentLive(identity, device)
                .onErrorResume(error -> environmentShadow(identity))
                .switchIfEmpty(environmentShadow(identity));
    }

    private Mono<Object> environmentLive(RequestIdentity identity, AuthorizedDevice device) {
        String code = metadataText(device, "code");
        if (device.hubDeviceId() == null || code == null) return Mono.empty();
        return iotCenterActions(code, identity)
                .map(actions -> actions.get("realTimeData"))
                .flatMap(actionId -> actionId == null ? Mono.empty()
                        : runQueryAction(identity, device.hubDeviceId(), actionId))
                .flatMap(payload -> {
                    Map<String, Object> reading = environmentReading(payload, "device");
                    return reading.isEmpty() ? Mono.empty() : Mono.<Object>just(reading);
                });
    }

    private Mono<Map<String, String>> iotCenterActions(String code, RequestIdentity identity) {
        return page("/micro-api/api/iot-center/device/page", Map.of("code", code), identity)
                .map(rows -> {
                    Map<String, String> actions = new LinkedHashMap<>();
                    if (!rows.isEmpty()) {
                        JsonNode actionList = rows.get(0).get("actionList");
                        if (actionList != null && actionList.isArray()) {
                            for (JsonNode action : actionList) {
                                String type = text(action, "actionType"), id = text(action, "id");
                                if (type != null && id != null) actions.put(type, id);
                            }
                        }
                    }
                    return actions;
                });
    }

    private Mono<JsonNode> runQueryAction(RequestIdentity identity, String hubDeviceId, String actionId) {
        return webClient.post().uri(builder -> builder
                        .path("/micro-api/api/iot-center/interactive/{hub}/{action}/down")
                        .build(hubDeviceId, actionId))
                .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .bodyValue(Map.of())
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 401 || status == 403) {
                        return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status)));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(new UpstreamUnavailableException("读取观察设备数据")));
                    }
                    return response.bodyToMono(JsonNode.class)
                            .switchIfEmpty(Mono.error(new UpstreamUnavailableException("读取观察设备数据")))
                            .flatMap(body -> {
                                int code = body.path("code").asInt(-1);
                                if (code == 401 || code == 403) {
                                    return Mono.error(new GatewayAuthorizationException(code));
                                }
                                if (code != 0 && code != 200) {
                                    return Mono.error(new UpstreamUnavailableException("读取观察设备数据"));
                                }
                                JsonNode content = body.get("content");
                                if (content != null && content.isTextual()) {
                                    try { content = JSON.readTree(content.asText()); }
                                    catch (Exception malformed) {
                                        return Mono.error(new UpstreamUnavailableException("读取观察设备数据响应"));
                                    }
                                }
                                return Mono.just(content);
                            });
                })
                .timeout(callTimeout);
    }

    private Mono<Object> environmentShadow(RequestIdentity identity) {
        return getJson("/micro-api/api/iot-center/home/yq/env/data", Map.of(), identity)
                .flatMap(body -> {
                    Map<String, Object> reading = environmentReading(body.get("content"), "shadow");
                    return reading.isEmpty() ? Mono.empty() : Mono.<Object>just(reading);
                })
                .onErrorResume(error -> Mono.empty());
    }

    private static Map<String, Object> environmentReading(JsonNode source, String origin) {
        Map<String, Object> reading = new LinkedHashMap<>();
        reading.put("source", origin);
        if (source != null && source.isObject()) {
            for (String key : List.of("temp", "humidity", "pm25", "pm10", "pm101")) {
                Double value = numberValue(source.get(key));
                if (value != null) reading.put(key, value);
            }
        }
        return reading.size() > 1 ? reading : Map.of();
    }

    private static Double numberValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.asDouble();
        if (value.isTextual()) {
            String digits = value.asText().replaceAll("[^0-9+\\-.]", "");
            if (digits.isBlank()) return null;
            try { return Double.parseDouble(digits); } catch (NumberFormatException invalid) { return null; }
        }
        return null;
    }

    private Mono<Map<String, Object>> energyStates(RequestIdentity identity, List<AuthorizedDevice> devices) {
        return page("/micro-api/api/energy/realTimeRead/page", Map.of(), identity)
                .map(rows -> {
                    Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
                    for (JsonNode row : rows) {
                        String equipmentId = text(row, "equipmentId");
                        Map<String, Object> reading = energyReading(row);
                        // Runtime's readEnergyReadings uses Map.set: if upstream repeats a
                        // meter across pages, the last row wins. Never fall back to another
                        // meter's reading when this id is absent.
                        if (equipmentId != null && reading != null) latest.put(equipmentId, reading);
                    }
                    Map<String, Object> states = new LinkedHashMap<>();
                    for (AuthorizedDevice device : devices) {
                        states.put(device.id(), latest.get(device.id()));
                    }
                    return states;
                });
    }

    private static Map<String, Object> energyReading(JsonNode row) {
        Map<String, Object> reading = new LinkedHashMap<>();
        String readTime = text(row, "readTime");
        if (readTime != null) reading.put("readTime", localDateTimeIso(readTime));
        for (String key : List.of("ia", "ib", "ic", "totalActivePower")) {
            Double value = numberValue(row.get(key));
            if (value != null) reading.put(key, value);
        }
        return reading.size() > 1 ? reading : null;
    }

    private static String localDateTimeIso(String raw) {
        try { return OffsetDateTime.parse(raw).toInstant().toString(); }
        catch (RuntimeException ignored) {
            try { return OffsetDateTime.parse(raw.replace(' ', 'T') + "+08:00").toInstant().toString(); }
            catch (RuntimeException invalid) { return raw; }
        }
    }

    @Override
    public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request) {
        return inspectDeviceCapabilities(identity, request, null);
    }

    @Override
    public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(
            RequestIdentity identity, DeviceCapabilityRequest request, String conversationId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(request, "request");
        if (identity.source() != IotSourceEnum.OFFICE && identity.source() != IotSourceEnum.COMPANY) {
            return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "设备能力仅支持 Office 或 Company 来源", 404));
        }
        if (request.candidateSets().isEmpty()) return Mono.error(new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集不能为空"));
        if (conversationId != null && !conversationId.isBlank() && candidateStore == null) {
            return Mono.error(new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503));
        }
        if (conversationId != null && !conversationId.isBlank() && identity.source() == IotSourceEnum.COMPANY) {
            return execute("inspect authorized Company device capabilities",
                    resolveCompanyCandidateDevices(identity, request, conversationId).map(devices ->
                            new DeviceCapabilityInspection(request.candidateSets().stream()
                                    .map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(),
                                    devices.stream().map(this::capability).toList())));
        }
        return execute("inspect authorized " + identity.source().wireValue() + " device capabilities",
                loadAuthorizedDeviceScope(identity).map(scope -> {
                    validateCapabilitySelections(request);
                    if (conversationId == null || conversationId.isBlank()) {
                        List<DeviceCapabilityInspection.DeviceCapability> capabilities = new ArrayList<>();
                        for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
                            for (String id : selection.deviceIds()) {
                                AuthorizedDevice device = scope.device(id).orElseThrow(() -> new BusinessException(
                                        "DEVICE_OUTSIDE_SCOPE", "设备不在当前授权范围内", 403));
                                capabilities.add(capability(device));
                            }
                        }
                        return new DeviceCapabilityInspection(request.candidateSets().stream()
                                .map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(), capabilities);
                    }
                    Map<String, AuthorizedDevice> devicesById = new LinkedHashMap<>();
                    for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
                        DeviceCandidateRecord set = requiredCandidateSet(identity, selection.candidateSetId(), conversationId);
                        AuthorizedDeviceScope setScope = candidateScope(scope, set);
                        if (!setScope.catalogFingerprint().equals(set.catalogFingerprint())) {
                            throw new BusinessException("CANDIDATE_SET_STALE", "设备候选集已过期，请重新检索", 409);
                        }
                        Map<String, AuthorizedDevice> members = membersById(set);
                        for (String id : selection.deviceIds()) {
                            AuthorizedDevice member = members.get(id);
                            if (member == null || setScope.device(id).isEmpty()) throw new BusinessException(
                                    "INVALID_DEVICE_REFERENCE", "设备不属于引用的候选集", 403);
                            devicesById.put(id, member);
                        }
                    }
                    List<DeviceCapabilityInspection.DeviceCapability> capabilities = request.candidateSets().stream()
                            .flatMap(selection -> selection.deviceIds().stream())
                            .map(devicesById::get).map(this::capability).toList();
                    return new DeviceCapabilityInspection(request.candidateSets().stream()
                            .map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(), capabilities);
                }));
    }

    /**
     * Reconstructs the exact scope a candidate set was discovered from. Office
     * discovery is focus-region filtered before its fingerprint is persisted, so
     * every later consumer must apply the same region filter or a valid set is
     * misreported as stale.
     */
    private static AuthorizedDeviceScope candidateScope(AuthorizedDeviceScope scope, DeviceCandidateRecord set) {
        String regionId = set.query().regionId();
        if (scope.source() != IotSourceEnum.OFFICE || regionId == null || regionId.isBlank()) return scope;
        return scope.retain(device -> device.officeIds().contains(regionId));
    }

    private DeviceCandidateRecord requiredCandidateSet(RequestIdentity identity, String candidateSetId,
                                                       String conversationId) {
        DeviceCandidateRecord set = candidateStore.find(candidateSetId, conversationId)
                .orElseThrow(() -> new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在", 404));
        if (!identity.tenantCode().equals(set.tenantCode())
                || !identity.ownerKey().equals(set.ownerKey())
                || identity.source() != set.source()
                || !conversationId.equals(set.conversationId())) {
            throw new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在", 404);
        }
        return set;
    }

    @Override
    public Mono<DeviceCandidateRecord> resolveDeviceCandidateSet(
            RequestIdentity identity, String candidateSetId, String conversationId) {
        return Mono.fromCallable(() -> requiredCandidateSet(identity, candidateSetId, conversationId));
    }

    private static Map<String, AuthorizedDevice> membersById(DeviceCandidateRecord set) {
        Map<String, AuthorizedDevice> members = new LinkedHashMap<>();
        for (AuthorizedDevice member : set.members()) members.put(member.id(), member);
        return members;
    }

    private static void validateCapabilitySelections(DeviceCapabilityRequest request) {
        if (request.candidateSets().isEmpty()) {
            throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备候选集不能为空");
        }
        Set<String> setIds = new java.util.HashSet<>();
        Set<String> deviceIds = new java.util.HashSet<>();
        for (DeviceCapabilityRequest.DeviceCapabilitySelection selection : request.candidateSets()) {
            if (!setIds.add(selection.candidateSetId())) throw new BusinessException(
                    "INVALID_DEVICE_REFERENCE", "设备候选集引用重复");
            if (selection.deviceIds().isEmpty()
                    || selection.deviceIds().stream().distinct().count() != selection.deviceIds().size()) {
                throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
            }
            for (String id : selection.deviceIds()) {
                if (!deviceIds.add(id)) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用重复");
            }
        }
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return identity.source() == IotSourceEnum.COMPANY
                ? execute("load authorized Company safety scope", loadCompanySafetyScope(identity))
                : loadAuthorizedDeviceScope(identity);
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedVideoScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return identity.source() == IotSourceEnum.COMPANY
                ? execute("load authorized Company video scope", loadCompanyVideoScope(identity))
                : loadAuthorizedDeviceScope(identity);
    }
    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (identity.source() != IotSourceEnum.COMPANY) {
            return Mono.error(new BusinessException("IOT_SOURCE_UNAVAILABLE", "公司电表授权范围仅支持公司来源", 404));
        }
        return execute("load authorized Company energy scope",
                loadCompanyScopeForKinds(identity, Set.of("energy")));
    }

    @Override
    public Mono<VideoPlaybackPreparation> prepareVideoPlayback(
            RequestIdentity identity, String deviceId, VideoPlaybackWindow window) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(window, "window");
        Mono<AuthorizedDeviceScope> scope = identity.source() == IotSourceEnum.COMPANY
                ? loadCompanyVideoScope(identity) : loadAuthorizedDeviceScope(identity);
        return execute("prepare " + identity.source().wireValue() + " video playback", scope.flatMap(current -> {
            AuthorizedDevice device = current.device(deviceId).orElse(null);
            if (device == null || !"video".equalsIgnoreCase(device.kind()) || !device.observationOnly()) {
                return Mono.error(new BusinessException("VIDEO_RECORDING_UNAVAILABLE", "所选设备不在当前视频授权范围内", 404));
            }
            return Mono.just(new VideoPlaybackPreparation(identity.source().wireValue(), device.id(), device.name(),
                    device.installAddress(), window.beginTime(), window.endTime()));
        }));
    }

    @Override
    public Mono<VideoStream> openVideoStream(RequestIdentity identity, String deviceId) {
        Objects.requireNonNull(identity, "identity");
        if (deviceId == null || deviceId.isBlank()) return Mono.empty();
        Mono<VideoStream> request = identity.source() == IotSourceEnum.COMPANY
                ? loadCompanyVideoScope(identity).flatMap(scope -> companyLive(scope, identity, deviceId))
                : loadAuthorizedDeviceScope(identity).flatMap(scope -> officeLive(scope, identity, deviceId));
        return execute("open " + identity.source().wireValue() + " video stream", request);
    }

    @Override
    public Mono<VideoStream> openVideoPlayback(
            RequestIdentity identity, String deviceId, VideoPlaybackWindow window) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(window, "window");
        if (deviceId == null || deviceId.isBlank()) return Mono.empty();
        Mono<VideoStream> request = (identity.source() == IotSourceEnum.COMPANY
                ? loadCompanyVideoScope(identity) : loadAuthorizedDeviceScope(identity))
                .flatMap(scope -> playback(scope, identity, deviceId, window));
        return execute("open " + identity.source().wireValue() + " video playback", request);
    }

    private Mono<AuthorizedDeviceScope> loadOfficeScope(RequestIdentity identity) {
        return readOfficeMembers(identity).flatMap(offices -> {
            List<Mono<List<AuthorizedDevice>>> jobs = new ArrayList<>();
            for (OfficeDeviceSource source : routes.officeDeviceSources()) {
                if (source.requiresOfficeContext()) {
                    for (JsonNode office : offices) {
                        String officeId = text(office, "elementId", "id");
                        String officeName = text(office, "elementName", "name");
                        Map<String, String> params = new LinkedHashMap<>(source.params());
                        params.put("elementId", officeId);
                        jobs.add(readSource(source, params, identity, officeId, officeName));
                    }
                } else {
                    jobs.add(readSource(source, source.params(), identity, null, null));
                }
            }
            // Runtime loads offices × sources with Promise.all (unbounded fan-out,
            // stable group order); the migration bounds it at six per call while
            // keeping the merge order identical to the serial concat shape.
            return Flux.fromIterable(jobs)
                    .flatMapSequential(job -> job, DIRECTORY_FANOUT_CONCURRENCY)
                    .collectList().map(groups -> mergeScope(identity, groups));
        });
    }

    private Mono<List<AuthorizedDevice>> readSource(
            OfficeDeviceSource source,
            Map<String, String> params,
            RequestIdentity identity,
            String officeId,
            String officeName) {
        return page(source.path(), params, identity)
                .map(rows -> rows.stream().map(row -> parseDevice(row, source, officeId, officeName)).toList());
    }

    private Mono<AuthorizedDeviceScope> loadCompanyScope(RequestIdentity identity) {
        return loadCompanyScopeForKinds(identity,
                Set.of("environment", "energy", "safety", "lighting", "access", "video", "ac"));
    }

    /**
     * Loads only the Company families needed to reauthorize the requested devices.
     * A meter read must not pay for safety detail, area trees and AC bindings it
     * never consults; the runtime reads states from the already-discovered catalog.
     */
    private Mono<AuthorizedDeviceScope> loadCompanyScopeForKinds(
            RequestIdentity identity, Set<String> kinds) {
        List<Mono<List<AuthorizedDevice>>> jobs = new ArrayList<>();
        if (kinds.contains("environment")) {
            jobs.add(page(routes.companyEnvironmentPath(), Map.of("modelCode", "YQ-ENV-SENSOR"), identity)
                    .map(rows -> rows.stream().map(this::parseEnvironmentObservation).toList()));
        }
        if (kinds.contains("energy")) {
            jobs.add(page(routes.companyEnergyPath(), Map.of(), identity)
                    .map(rows -> rows.stream().map(this::parseEnergyObservation).toList()));
        }
        if (kinds.contains("safety")) jobs.add(collectCompanySafety(identity));
        if (kinds.contains("lighting")) jobs.add(collectCompanySmart(identity, "lighting"));
        if (kinds.contains("access")) jobs.add(collectCompanySmart(identity, "access"));
        if (kinds.contains("video")) jobs.add(collectCompanySmart(identity, "monitor"));
        if (kinds.contains("ac")) jobs.add(collectCompanyAc(identity));
        if (jobs.isEmpty()) return Mono.error(new BusinessException(
                "INVALID_DEVICE_REFERENCE", "设备候选集不包含可读取的设备类型", 403));
        return Flux.fromIterable(jobs).flatMapSequential(job -> job, DIRECTORY_FANOUT_CONCURRENCY).collectList().map(groups -> {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (List<AuthorizedDevice> group : groups) {
                for (AuthorizedDevice device : group) unique.putIfAbsent(device.kind() + ":" + device.id(), device);
            }
            return (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, List.copyOf(unique.values()));
        });
    }

    private Mono<AuthorizedDeviceScope> loadCompanySafetyScope(RequestIdentity identity) {
        return collectCompanySafety(identity)
                .map(devices -> (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, devices));
    }

    private Mono<AuthorizedDeviceScope> loadCompanyVideoScope(RequestIdentity identity) {
        // Match Runtime's one-minute video-only authorization reuse; never cache stream URLs.
        // The credential digest prevents token rotation from reusing an earlier authorization.
        String credentialKey;
        try {
            credentialKey = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(identity.authorization().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String key = "video\u0000" + identity.tenantCode() + "\u0000" + identity.ownerKey() + "\u0000" + credentialKey;
        return Mono.defer(() -> cachedReadScope(key, collectCompanySmart(identity, "monitor")
                .map(devices -> (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, devices)), VIDEO_SCOPE_REUSE_MILLIS));
    }

    private Mono<List<AuthorizedDevice>> collectCompanySafety(RequestIdentity identity) {
        return page(routes.companySafetyPath(), Map.of("type", "FIRE"), identity)
                .flatMapMany(rows -> Flux.fromIterable(rows)
                        .filter(WebClientIotAdapter::isFireSafetyRecord)
                        // Runtime enriches safety rows with detail reads in batches
                        // of three; the migration keeps the stable row order with a
                        // bounded queue of six instead of a fully serial concat.
                        .flatMapSequential(row -> parseSafetyObservation(row, identity), READ_QUEUE_CONCURRENCY))
                .collectList();
    }

    private Mono<List<AuthorizedDevice>> collectCompanySmart(RequestIdentity identity, String family) {
        return areaLeaves(routes.companySmartBasePath() + "/" + family + "/myAreaList", identity)
                .flatMapMany(Flux::fromIterable)
                // Runtime fetches every leaf's devicePage with one Promise.all;
                // the migration bounds that fan-out at six, stable leaf order.
                .flatMapSequential(leaf -> page(routes.companySmartBasePath() + "/" + family + "/devicePage",
                                Map.of("areaFullCode", leaf.code()), identity)
                        .map(rows -> rows.stream().map(row -> parseSmart(row, family, leaf)).toList()),
                        DIRECTORY_FANOUT_CONCURRENCY)
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
        // Runtime walks the area tree level by level with one Promise.all over the
        // siblings; the migration keeps sibling fan-out concurrent but bounded at six.
        return getJson(path, params, identity).flatMapMany(body -> Flux.fromIterable(areaRows(body))
                .flatMapSequential(node -> {
                    String id = text(node, "id", "elementId");
                    if (id == null) return Flux.error(new UpstreamUnavailableException("智慧设备区域格式无效"));
                    String name = text(node, "name", "elementName");
                    List<String> next = name != null && !"全部".equals(name) ? append(names, name) : names;
                    String code = text(node, "fullCode", "areaFullCode");
                    return areaChildren(path, id, next, identity).switchIfEmpty(code == null
                            ? Flux.error(new UpstreamUnavailableException("智慧设备区域缺少区域编码"))
                            : Flux.just(new AreaLeaf(code, next)));
                }, DIRECTORY_FANOUT_CONCURRENCY));
    }

    private static List<JsonNode> areaRows(JsonNode body) {
        if (body.isArray()) return toList(body);
        if (body.path("code").asInt() == 204) return List.of();
        JsonNode content = body.get("content");
        if (content != null && content.isArray()) return toList(content);
        if (content == null || !content.isObject()) throw new UpstreamUnavailableException("智慧设备区域响应格式无效");
        JsonNode rows = content.get("content");
        if (rows == null || !rows.isArray() || !content.path("last").isBoolean()) {
            throw new UpstreamUnavailableException("智慧设备区域分页格式无效");
        }
        if (!content.path("last").asBoolean()) throw new UpstreamUnavailableException("智慧设备区域分页未完成");
        return toList(rows);
    }

    private Mono<List<AuthorizedDevice>> collectCompanyAc(RequestIdentity identity) {
        Mono<List<AuthorizedDevice>> scoped = areaLeaves(routes.companyAcAreaPath(), identity)
                .flatMapMany(Flux::fromIterable)
                .flatMapSequential(leaf -> page(routes.companyAcDevicePath(), Map.of("areaFullCode", leaf.code()), identity)
                        .map(rows -> rows.stream().map(row -> parseAc(row, leaf)).toList()),
                        DIRECTORY_FANOUT_CONCURRENCY)
                .collectList().map(this::mergeAcDevices);
        return scoped.zipWith(pageAcBinding(routes.companyAcBindingPath(), identity))
                .map(tuple -> {
                    Map<String, JsonNode> bindings = new LinkedHashMap<>();
                    for (JsonNode row : tuple.getT2()) {
                        if (!"YQ_AIR_CONDITION".equals(text(row, "modelCode"))) continue;
                        String id = text(row, "id");
                        if (id != null) bindings.put(id, row);
                    }
                    return tuple.getT1().stream().map(device -> {
                        JsonNode binding = bindings.get(device.hubDeviceId());
                        String providerCode = binding == null ? null : text(binding, "code");
                        String airCondition = binding == null ? null : actionId(binding, "airCondition");
                        String acDetail = binding == null ? null : actionId(binding, "acDetail");
                        boolean validBinding = providerCode != null && airCondition != null && acDetail != null;
                        Map<String, Object> metadata = new LinkedHashMap<>(device.metadata());
                        putMetadata(metadata, "companyAcActionId", validBinding ? airCondition : null);
                        putMetadata(metadata, "companyAcDetailActionId", validBinding ? acDetail : null);
                        return new AuthorizedDevice(device.id(), device.kind(), device.name(), device.installAddress(),
                                !validBinding, validBinding ? providerCode : null, device.officeDeviceRef(),
                                device.hubDeviceId(), device.smartAreaCode(), device.locationPaths(), device.officeIds(), metadata);
                    }).toList();
                });
    }

    private List<AuthorizedDevice> mergeAcDevices(List<List<AuthorizedDevice>> groups) {
        Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
        for (List<AuthorizedDevice> group : groups) {
            for (AuthorizedDevice candidate : group) {
                String key = candidate.hubDeviceId() == null ? candidate.id() : candidate.hubDeviceId();
                AuthorizedDevice prior = unique.get(key);
                unique.put(key, prior == null ? candidate : mergeAcDevice(prior, candidate));
            }
        }
        return List.copyOf(unique.values());
    }

    private static AuthorizedDevice mergeAcDevice(AuthorizedDevice first, AuthorizedDevice duplicate) {
        List<List<String>> paths = new ArrayList<>(first.locationPaths());
        for (List<String> path : duplicate.locationPaths()) {
            if (paths.stream().anyMatch(existing -> isPrefix(existing, path))) continue;
            paths.removeIf(existing -> isPrefix(path, existing));
            paths.add(path);
        }
        return new AuthorizedDevice(first.id(), first.kind(), first.name(), first.installAddress(),
                first.observationOnly(), first.controlRef(), first.officeDeviceRef(), first.hubDeviceId(),
                first.smartAreaCode(), paths, first.officeIds(), first.metadata());
    }

    private static boolean isPrefix(List<String> prefix, List<String> value) {
        if (prefix.size() > value.size()) return false;
        return java.util.stream.IntStream.range(0, prefix.size()).allMatch(index -> prefix.get(index).equals(value.get(index)));
    }

    private static String actionId(JsonNode binding, String type) {
        JsonNode actions = binding.get("actionList");
        if (actions != null && actions.isArray()) for (JsonNode action : actions)
            if (type.equals(text(action, "actionType"))) return text(action, "id");
        return null;
    }

    private Mono<VideoStream> officeLive(AuthorizedDeviceScope scope, RequestIdentity identity, String deviceId) {
        AuthorizedDevice device = scope.device(deviceId).orElse(null);
        if (device == null || !"video".equalsIgnoreCase(device.kind())) return Mono.empty();
        String hub = device.hubDeviceId();
        String code = metadataText(device, "code");
        if (hub == null || code == null) return Mono.empty();
        return page("/micro-api/api/iot-center/device/page", Map.of("code", code), identity)
                .flatMap(rows -> {
                    String action = rows.isEmpty() ? null : actionId(rows.get(0), "view");
                    if (action == null) return Mono.empty();
                    return postVideoAction(identity, hub, action, Map.of(), false, true)
                            .map(url -> new VideoStream(device.name(), url));
                });
    }

    private Mono<VideoStream> companyLive(AuthorizedDeviceScope scope, RequestIdentity identity, String deviceId) {
        AuthorizedDevice device = scope.device(deviceId).orElse(null);
        if (device == null || !"video".equalsIgnoreCase(device.kind()) || device.officeDeviceRef() == null
                || routes.companyMonitorDownPath() == null) return Mono.empty();
        return postVideoPath(identity, routes.companyMonitorDownPath(),
                        Map.of("monitorAreaDeviceId", device.officeDeviceRef()), false, false)
                .map(url -> new VideoStream(device.name(), url));
    }

    private Mono<VideoStream> playback(
            AuthorizedDeviceScope scope, RequestIdentity identity, String deviceId, VideoPlaybackWindow window) {
        AuthorizedDevice device = scope.device(deviceId).orElse(null);
        if (device == null || !"video".equalsIgnoreCase(device.kind()) || device.hubDeviceId() == null) {
            return Mono.empty();
        }
        String action = "bec190c9819348e08b203a95b7629ad8";
        String path = "/micro-api/api/iot-center/interactive/{hub}/{action}/down";
        return postVideoPath(identity, path, Map.of("beginTime", window.beginTime(), "endTime", window.endTime()),
                        true, true, device.hubDeviceId(), action)
                .map(url -> new VideoStream(device.name(), url));
    }

    private Mono<String> postVideoAction(
            RequestIdentity identity, String hubDeviceId, String actionId, Map<String, Object> payload,
            boolean playback, boolean failOnError) {
        String path = "/micro-api/api/iot-center/interactive/{hub}/{action}/down";
        return postVideoPath(identity, path, payload, playback, failOnError, hubDeviceId, actionId);
    }

    private Mono<String> postVideoPath(
            RequestIdentity identity, String path, Map<String, ?> payload,
            boolean playback, boolean failOnError, String... pathValues) {
        return webClient.post().uri(builder -> {
                    var uri = builder.path(path);
                    return pathValues.length == 0 ? uri.build() : uri.build((Object[]) pathValues);
                })
                .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .bodyValue(payload)
                .exchangeToMono(response -> decodeVideo(response, playback, failOnError))
                .timeout(callTimeout);
    }

    private Mono<String> decodeVideo(ClientResponse response, boolean playback, boolean failOnError) {
        int status = response.statusCode().value();
        if (status == 401 || status == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status)));
        }
        if (!response.statusCode().is2xxSuccessful()) {
            return response.releaseBody().then(failOnError
                    ? Mono.error(new UpstreamUnavailableException("视频取流上游请求失败"))
                    : Mono.empty());
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.empty())
                .flatMap(body -> {
                    if (body == null || !body.isObject()) return failOnError
                            ? Mono.error(new UpstreamUnavailableException("视频取流上游响应格式无效")) : Mono.empty();
                    int code = body.path("code").asInt(-1);
                    if (code == 401 || code == 403) return Mono.error(new GatewayAuthorizationException(code));
                    if (playback && code == 207) return Mono.error(new VideoStreamBusyException());
                    if (code != 0 && code != 200) return failOnError
                            ? Mono.error(new UpstreamUnavailableException("视频取流上游返回失败")) : Mono.empty();
                    JsonNode content = body.get("content");
                    if (content != null && content.isTextual()) {
                        try {
                            content = JSON.readTree(content.asText());
                        } catch (Exception malformed) {
                            return failOnError ? Mono.error(new UpstreamUnavailableException("视频取流上游响应格式无效"))
                                    : Mono.empty();
                        }
                    }
                    String url = text(content, "url");
                    if (!contentIsResult(content) || url == null || url.isBlank()
                            || (playback && !(url.startsWith("ws://") || url.startsWith("wss://")))) {
                        return Mono.empty();
                    }
                    return Mono.just(url);
                });
    }

    private static boolean contentIsResult(JsonNode content) {
        return content != null && content.isObject() && content.path("result").asBoolean(false);
    }

    private static String metadataText(AuthorizedDevice device, String key) {
        Object value = device.metadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private AuthorizedDevice parseEnvironmentObservation(JsonNode row) {
        String id = text(row, "id"), name = text(row, "name");
        if (id == null || name == null) throw new UpstreamUnavailableException("读取环境设备响应");
        Map<String, Object> metadata = new LinkedHashMap<>();
        putMetadata(metadata, "code", text(row, "code"));
        putMetadata(metadata, "actionList", row.get("actionList"));
        return new AuthorizedDevice(id, "environment", name, text(row, "installAddress"), true, null, id,
                text(row, "hubDeviceId", "id"), null, List.of(), List.of(), metadata);
    }

    private static final java.util.regex.Pattern ENERGY_TARIFF =
            java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");

    private AuthorizedDevice parseEnergyObservation(JsonNode row) {
        String id = text(row, "id"), name = text(row, "equipmentName");
        if (id == null || name == null) throw new UpstreamUnavailableException("读取能耗设备响应");
        Map<String, Object> metadata = new LinkedHashMap<>();
        putMetadata(metadata, "feeSchemeName", text(row, "feeSchemeName"));
        putMetadata(metadata, "energyType", text(row, "energyType"));
        String scheme = text(row, "feeSchemeName");
        if (scheme != null) {
            var price = ENERGY_TARIFF.matcher(scheme);
            if (price.find()) metadata.put("tariff", Double.parseDouble(price.group(1)));
        }
        return new AuthorizedDevice(id, "energy", name, text(row, "areaName"), true, null, id,
                text(row, "hubDeviceId", "id"), null, List.of(), List.of(), metadata);
    }

    private static boolean isFireSafetyRecord(JsonNode row) {
        String type = text(row, "type", "deviceType", "category");
        return type == null || "FIRE".equalsIgnoreCase(type);
    }

    private Mono<AuthorizedDevice> parseSafetyObservation(JsonNode row, RequestIdentity identity) {
        String id = text(row, "id", "deviceId");
        String hubDeviceId = text(row, "hubDeviceId");
        String name = text(row, "name", "deviceName");
        if ((id == null || name == null) && routes.companySafetyDetailPath() != null && hubDeviceId != null) {
            String detailPath = routes.companySafetyDetailPath() + (routes.companySafetyDetailPath().endsWith("/") ? hubDeviceId : "/" + hubDeviceId);
            return getJson(detailPath, Map.of(), identity).map(WebClientIotAdapter::detailObject)
                    .flatMap(detail -> hubDeviceId.equals(text(detail, "id"))
                            ? Mono.just(parseSafety(row, detail)) : Mono.fromCallable(() -> parseSafety(row, null)));
        }
        return Mono.fromCallable(() -> parseSafety(row, null));
    }

    private AuthorizedDevice parseSafety(JsonNode row, JsonNode detail) {
        String id = text(row, "id", "deviceId");
        String hubDeviceId = text(row, "hubDeviceId");
        String name = textFrom(row, detail, "name", "deviceName");
        if (id == null || name == null) throw new UpstreamUnavailableException("读取安全设备响应");
        JsonNode currentDevice = nodeFrom(row, detail, "currentDevice");
        String address = textFrom(row, detail, "installAddress", "installAddr", "location", "areaName");
        if (address == null && currentDevice != null) address = text(currentDevice, "installAddress", "installAddr", "location", "areaName");
        Map<String, Object> metadata = new LinkedHashMap<>();
        putMetadata(metadata, "subtype", textFrom(row, detail, "subtype", "subType"));
        putMetadata(metadata, "currentDevice", currentDevice);
        return new AuthorizedDevice(id, "safety", name, address, true, null, id, hubDeviceId, null, List.of(), List.of(), metadata);
    }

    private static JsonNode detailObject(JsonNode body) {
        JsonNode content = body.get("content");
        if (content != null && content.isObject()) return content;
        if (body.isObject()) return body;
        throw new UpstreamUnavailableException("读取安全设备详情响应");
    }

    private static void putMetadata(Map<String, Object> metadata, String key, Object value) {
        if (value != null) metadata.put(key, value);
    }

    private AuthorizedDevice parseSmart(JsonNode row, String family, AreaLeaf leaf) {
        String id = text(row, "id", "officeDeviceRef"), hub = text(row, "hubDeviceId");
        String monitorReference = text(row, "monitorAreaDeviceId", "officeDeviceRef", "id");
        if (id == null || hub == null || monitorReference == null) throw new UpstreamUnavailableException("智慧设备缺少控制绑定");
        String kind = "monitor".equals(family) ? "video" : family;
        String name = text(row, "name", "deviceName");
        return new AuthorizedDevice(id, kind, name == null ? kind : name, text(row, "installAddress", "installAddr"),
                "video".equals(kind), "video".equals(kind) ? null : id, monitorReference, hub, leaf.code(),
                List.of(leaf.path()), List.of(), Map.of());
    }

    private AuthorizedDevice parseAc(JsonNode row, AreaLeaf leaf) {
        String hub = text(row, "hubDeviceId", "id");
        if (hub == null) throw new UpstreamUnavailableException("空调设备缺少控制绑定");
        String name = text(row, "name", "deviceName");
        return new AuthorizedDevice(hub, "ac", name == null ? "空调" : name, text(row, "installAddress", "installAddr"),
                true, null, hub, hub, leaf.code(), List.of(leaf.path()), List.of(), Map.of());
    }

    private static List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values); result.add(value); return result;
    }

    private record AreaLeaf(String code, List<String> path) { }

    private Mono<AuthorizedDeviceScope> loadPaginatedScope(RequestIdentity identity) {
        return page(routes.path(identity.source()), Map.of(), identity).map(records -> {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (JsonNode record : records) {
                AuthorizedDevice device = parseDevice(record, null, null, null);
                AuthorizedDevice previous = unique.putIfAbsent(device.kind() + ":" + device.id(), device);
                if (previous != null && !previous.equals(device)) {
                    throw new UpstreamUnavailableException("读取物联授权设备范围响应");
                }
            }
            return (AuthorizedDeviceScope) new AuthorizedDeviceScopeSnapshot(identity, new ArrayList<>(unique.values()));
        });
    }

    /**
     * State records for exactly the requested devices, replacing the read-everything
     * directory sweep. Runtime readStateObservations derives coarse state from the
     * scoped office pages only; the offices come from the devices' own officeIds, so
     * the member directory is not reread and unrelated kinds, offices and association
     * sources are not fetched. Independent (source, office) pages share the bounded
     * read queue and keep first-record-wins precedence in configured source order.
     * Devices with no matching source page stay honestly unknown (null), never false.
     */
    private Mono<Map<String, JsonNode>> readOfficeStateRecords(
            RequestIdentity identity, Collection<AuthorizedDevice> requested) {
        // Per-kind office sets: a kind's pages are fetched only for the offices where
        // that kind was actually requested, never the union crossed with every kind.
        Map<String, Set<String>> officesByKind = new LinkedHashMap<>();
        for (AuthorizedDevice device : requested) {
            String kind = device.kind().toLowerCase(Locale.ROOT);
            if (!STATE_SOURCE_KINDS.contains(kind)) continue;
            Set<String> offices = officesByKind.computeIfAbsent(kind, ignored -> new java.util.LinkedHashSet<>());
            for (String officeId : device.officeIds()) {
                if (officeId != null && !officeId.isBlank()) offices.add(officeId);
            }
        }
        List<Mono<Map<String, Object>>> reads = new ArrayList<>();
        for (OfficeDeviceSource source : routes.officeDeviceSources()) {
            Set<String> offices = source.requiresOfficeContext()
                    ? officesByKind.get(source.kind().toLowerCase(Locale.ROOT)) : null;
            if (offices == null || offices.isEmpty()) continue;
            for (String officeId : offices) {
                Map<String, String> params = new LinkedHashMap<>(source.params());
                params.put("elementId", officeId);
                reads.add(page(source.path(), params, identity).map(rows -> {
                    Map<String, Object> records = new LinkedHashMap<>();
                    for (JsonNode row : rows) {
                        String id = text(row, "id");
                        if (id != null) records.putIfAbsent(id, row);
                    }
                    return records;
                }));
            }
        }
        return drainQueue(reads).map(collected -> {
            Map<String, JsonNode> recordsById = new LinkedHashMap<>();
            collected.forEach((id, record) -> recordsById.put(id, (JsonNode) record));
            return recordsById;
        });
    }

    private AuthorizedDeviceScope mergeScope(RequestIdentity identity, List<List<AuthorizedDevice>> groups) {
        List<AuthorizedDevice> records = groups.stream().flatMap(List::stream).toList();
        // A scoped record and an account-level association record may expose different
        // ids for one physical device. Join provenance by officeDeviceRef, then by
        // physical hub; the scoped record stays the user-facing identity.
        Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
        for (AuthorizedDevice candidate : records) {
            String key = candidate.kind() + ":" + candidate.officeDeviceRef();
            AuthorizedDevice prior = unique.get(key);
            unique.put(key, prior == null ? candidate : mergeOfficeMembership(prior, candidate));
        }
        Map<String, String> controlRefByHub = new LinkedHashMap<>();
        for (AuthorizedDevice device : records) {
            if (device.controlRef() != null && device.hubDeviceId() != null && !device.observationOnly()) {
                controlRefByHub.putIfAbsent(device.hubDeviceId(), device.controlRef());
            }
        }
        for (Map.Entry<String, AuthorizedDevice> entry : unique.entrySet()) {
            AuthorizedDevice device = entry.getValue();
            if (device.controlRef() == null && device.hubDeviceId() != null) {
                String controlRef = controlRefByHub.get(device.hubDeviceId());
                if (controlRef != null) entry.setValue(withControlRef(device, controlRef));
            }
        }
        Map<String, AuthorizedDevice> physical = new LinkedHashMap<>();
        for (AuthorizedDevice device : unique.values()) {
            String key = device.hubDeviceId() == null
                    ? device.kind() + ":ref:" + device.officeDeviceRef()
                    : device.kind() + ":hub:" + device.hubDeviceId();
            AuthorizedDevice prior = physical.get(key);
            if (prior == null) {
                physical.put(key, device);
                continue;
            }
            boolean priorAssociationOnly = associationOnly(prior);
            boolean candidateAssociationOnly = associationOnly(device);
            AuthorizedDevice canonical = priorAssociationOnly && !candidateAssociationOnly ? device : prior;
            AuthorizedDevice secondary = canonical == prior ? device : prior;
            physical.put(key, mergeOfficeMembership(canonical, secondary));
        }
        // Account-level association directories are controlRef join material, not an
        // authorization scope. A device with no scoped source must not be discoverable.
        List<AuthorizedDevice> authorized = physical.values().stream()
                .filter(device -> !associationOnly(device)).toList();
        return new AuthorizedDeviceScopeSnapshot(identity, authorized);
    }

    @Override
    public Mono<String> readCreatorDisplayName(RequestIdentity identity) {
        return webClient.get().uri("/sso-api/account/me")
                .headers(headers -> {
                    headers.set("Authorization", identity.authorization());
                    headers.set("tenantcode", identity.tenantCode());
                })
                .retrieve().bodyToMono(JsonNode.class).timeout(callTimeout)
                .map(body -> {
                    int code = body.path("code").asInt(-1);
                    JsonNode name = body.path("content").path("personalName");
                    return (code == 0 || code == 200) && name.isTextual() && !name.asText().isBlank()
                            ? name.asText().trim() : "成员";
                })
                .defaultIfEmpty("成员").onErrorReturn("成员");
    }

    private static boolean associationOnly(AuthorizedDevice device) {
        return !device.sourceKeys().isEmpty()
                && device.sourceKeys().stream().allMatch(key -> key.startsWith("office-association."));
    }

    private static AuthorizedDevice withControlRef(AuthorizedDevice device, String controlRef) {
        return new AuthorizedDevice(device.id(), device.kind(), device.name(), device.installAddress(),
                device.observationOnly(), controlRef, device.officeDeviceRef(), device.hubDeviceId(),
                device.smartAreaCode(), device.locationPaths(), device.officeIds(), device.metadata());
    }

    private static AuthorizedDevice mergeOfficeMembership(AuthorizedDevice first, AuthorizedDevice duplicate) {
        List<String> officeIds = new ArrayList<>(first.officeIds());
        for (String officeId : duplicate.officeIds()) {
            if (!officeIds.contains(officeId)) officeIds.add(officeId);
        }
        officeIds = officeIds.stream().distinct().sorted().toList();
        Map<String, Object> metadata = new LinkedHashMap<>(first.metadata());
        List<String> sourceKeys = new ArrayList<>(first.sourceKeys());
        sourceKeys.addAll(duplicate.sourceKeys());
        metadata.put("sourceKeys", sourceKeys.stream().distinct().sorted().toList());
        List<List<String>> locationPaths = new ArrayList<>(first.locationPaths());
        for (List<String> path : duplicate.locationPaths()) {
            if (locationPaths.stream().noneMatch(existing -> isPrefix(existing, path))) locationPaths.add(path);
        }
        return new AuthorizedDevice(first.id(), first.kind(), first.name(), first.installAddress(),
                first.observationOnly(),
                first.controlRef() == null ? duplicate.controlRef() : first.controlRef(),
                first.officeDeviceRef(), first.hubDeviceId(), first.smartAreaCode(), locationPaths, officeIds, metadata);
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

    private Mono<List<JsonNode>> pageAcBinding(String path, RequestIdentity identity) {
        return Flux.range(0, 10)
                .concatMap(page -> getJson(path, withPage(Map.of("modelCode", "YQ_AIR_CONDITION"), page), identity).map(WebClientIotAdapter::acBindingPage))
                .takeUntil(PageResult::last)
                .collectList()
                .flatMap(pages -> {
                    if (pages.isEmpty() || !pages.get(pages.size() - 1).last()) {
                        return Mono.error(new UpstreamUnavailableException("读取空调绑定分页未完成"));
                    }
                    return Mono.just(pages.stream().flatMap(result -> result.records().stream()).toList());
                });
    }

    private static PageResult acBindingPage(JsonNode body) {
        if (body.path("code").asInt() == 204) return PageResult.empty();
        JsonNode content = body.get("content");
        if (content == null || !content.isObject()) throw new UpstreamUnavailableException("读取空调绑定响应");
        JsonNode rowsNode = content.get("content");
        if (rowsNode == null || !rowsNode.isArray()) throw new UpstreamUnavailableException("读取空调绑定响应");
        List<JsonNode> rows = toList(rowsNode);
        boolean last = content.path("last").isBoolean() ? content.path("last").asBoolean() : rows.size() < 100;
        return new PageResult(rows, last);
    }

    private Mono<JsonNode> getJson(String path, Map<String, String> params, RequestIdentity identity) {
        return webClient.get().uri(builder -> {
            var uri = builder.path(path);
            params.forEach(uri::queryParam);
            return uri.build();
        }).headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> decodeJson(response, "读取物联只读目录"))
                .timeout(callTimeout);
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
                AuthorizedDevice device = parseDevice(record, null, null, null);
                AuthorizedDevice prior = unique.putIfAbsent(device.kind() + ":" + device.id(), device);
                if (prior != null && !prior.equals(device)) return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
            }
            return Mono.just(new AuthorizedDeviceScopeSnapshot(identity, new ArrayList<>(unique.values())));
        } catch (IllegalArgumentException malformed) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        }
    }

    private AuthorizedDevice parseDevice(JsonNode record, OfficeDeviceSource source, String officeId, String officeName) {
        if (record == null || !record.isObject()) throw new IllegalArgumentException("device record is not an object");
        String id = text(record, "id", "deviceId");
        String kind = source == null ? text(record, "kind", "deviceKind") : source.kind();
        String name = text(record, "name", "deviceName");
        String address = text(record, "installAddress", "installAddr");
        if (id == null || kind == null || name == null) throw new IllegalArgumentException("device identity is incomplete");
        if (address == null) address = officeName == null ? "" : officeName;
        boolean observationOnly = "video".equalsIgnoreCase(kind) || record.path("observationOnly").asBoolean(false);
        boolean association = source != null && source.key().startsWith("office-association.");
        Map<String, Object> metadata = new LinkedHashMap<>();
        putMetadata(metadata, "code", text(record, "code"));
        putMetadata(metadata, "actionList", record.get("actionList"));
        putMetadata(metadata, "sourceKeys", List.of(source == null ? "microbrain." + kind : source.key()));
        return new AuthorizedDevice(id, kind, name, address, observationOnly,
                association ? id : text(record, "controlRef"),
                text(record, "officeDeviceRef", "id"), text(record, "hubDeviceId"),
                text(record, "smartAreaCode"), List.of(), officeId == null ? List.of() : List.of(officeId), metadata);
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
                ? AcCommandCapabilities.parameters() : null;
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
        if (node == null || !node.isObject()) return null;
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull() && !value.asText().isBlank()) return value.asText().trim();
        }
        return null;
    }

    private static String textFrom(JsonNode first, JsonNode second, String... fields) {
        String value = text(first, fields);
        return value == null ? text(second, fields) : value;
    }

    private static JsonNode nodeFrom(JsonNode first, JsonNode second, String field) {
        JsonNode value = first == null ? null : first.get(field);
        return value == null || value.isNull() ? second == null ? null : second.get(field) : value;
    }

    private <T> Mono<T> execute(String operation, Mono<T> request) {
        return callPolicy.execute(operation, request).timeout(compositionTimeout)
                .onErrorMap(error -> error instanceof TimeoutException, error -> new UpstreamUnavailableException(operation + "超时"))
                .onErrorMap(error -> !(error instanceof BusinessException) && !(error instanceof CancellationException), error -> new UpstreamUnavailableException(operation + "失败"));
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("callTimeout must be positive");
        return timeout;
    }

    private record ScopeEntry(AuthorizedDeviceScope scope, long expiresAt) { }
    private record PageResult(List<JsonNode> records, boolean last) {
        static PageResult empty() { return new PageResult(List.of(), true); }
    }
}
