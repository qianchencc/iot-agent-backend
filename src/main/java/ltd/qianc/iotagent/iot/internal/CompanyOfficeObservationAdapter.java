package ltd.qianc.iotagent.iot.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AutomationSummaryReader;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.region.RegionSeenStore;
import reactor.core.publisher.Mono;

/**
 * Local Runtime composition for the company dashboard and Office regions.
 *
 * <p>This adapter deliberately never calls the upstream DASHBOARD operation. It
 * obtains the current authorization catalog from {@link IotAdapter}, then reads
 * each observation area independently through the existing observation boundary.
 * The small public ObservationAdapter interface therefore remains the only route
 * contract while composition, caching and authorization details stay internal.</p>
 */
public final class CompanyOfficeObservationAdapter implements ObservationAdapter {
    private static final String REGION_UNAVAILABLE = "当前用户区域暂不可用，请稍后重试";
    private static final long FRESH_MILLIS = 15_000L;
    private static final long STALE_MILLIS = 5 * 60_000L;

    private final ObservationAdapter upstream;
    private final IotAdapter iot;
    private final AutomationSummaryReader automation;
    private final RegionSeenStore regionSeen;
    private final Clock clock;
    private final ConcurrentMap<String, CacheEntry> dashboardCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Mono<ObservationResponse>> dashboardJobs = new ConcurrentHashMap<>();

    public CompanyOfficeObservationAdapter(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            Clock clock) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.automation = Objects.requireNonNull(automation, "automation");
        this.regionSeen = regionSeen;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.operation()) {
            case DASHBOARD -> dashboard(request);
            case REGION_LIST -> regionList(request);
            case REGION_DEVICES -> regionDevices(request);
            case REGION_STATES -> regionStates(request);
            case REGION_SEEN -> regionSeen(request);
            default -> upstream.read(request);
        };
    }

    private Mono<ObservationResponse> dashboard(ObservationRequest request) {
        requireSource(request.identity(), IotSource.COMPANY, "DASHBOARD_SOURCE_UNAVAILABLE", "仪表盘仅支持公司来源");
        String section = request.parameters().get("section");
        String key = cacheKey(request.identity(), section);
        // Every request rechecks the current Company authorization. A cached
        // dashboard can never survive a revoked scope or a failed revalidation.
        return iot.loadAuthorizedDeviceScope(request.identity()).flatMap(scope -> {
            long now = clock.millis();
            CacheEntry existing = dashboardCache.get(key);
            if (existing != null && now - existing.at < FRESH_MILLIS) return Mono.just(existing.response);
            Mono<ObservationResponse> running = dashboardJobs.get(key);
            if (existing != null && now - existing.at < STALE_MILLIS) {
                if (running == null) startDashboardRefresh(key, request, scope);
                return Mono.just(existing.response);
            }
            if (running != null) return running;
            return startDashboardRefresh(key, request, scope);
        }).doOnError(error -> {
            if (isAuthorizationFailure(error)) invalidateDashboard(key);
        });
    }

    private Mono<ObservationResponse> startDashboardRefresh(
            String key, ObservationRequest request, AuthorizedDeviceScope scope) {
        Mono<ObservationResponse> fresh = loadDashboard(request, scope)
                .doOnNext(response -> {
                    // Partial facts and explicit errors are intentionally not cached.
                    Object errors = response.payload().get("errors");
                    boolean complete = !(errors instanceof Collection<?> values) || values.isEmpty();
                    if (complete && response.failures().isEmpty() && response.status() == ObservationStatus.AVAILABLE) {
                        dashboardCache.put(key, new CacheEntry(response, clock.millis()));
                    }
                })
                .doOnError(error -> {
                    if (isAuthorizationFailure(error)) invalidateDashboard(key);
                })
                .cache();
        Mono<ObservationResponse> prior = dashboardJobs.putIfAbsent(key, fresh);
        if (prior != null) return prior;
        fresh.doFinally(signal -> dashboardJobs.remove(key, fresh)).subscribe(null, ignored -> { });
        return fresh;
    }

    private Mono<ObservationResponse> loadDashboard(ObservationRequest request, AuthorizedDeviceScope scope) {
        RequestIdentity identity = request.identity();
        List<AuthorizedDevice> environment = devices(scope, "environment");
        List<AuthorizedDevice> energy = devices(scope, "energy");
        List<AuthorizedDevice> safety = devices(scope, "safety");
        String section = request.parameters().get("section");
        boolean includeEnvironment = section == null || section.equals("summary") || section.equals("environment");
        boolean includeEnergy = section == null || section.equals("energy");
        boolean includeAlerts = section == null || section.equals("summary") || section.equals("alerts");
        boolean includeAutomation = section == null || section.equals("summary") || section.equals("automation");

        Mono<AreaRead> environmentRead = includeEnvironment
                ? readArea(identity, ObservationOperation.ENVIRONMENT_HISTORY, environment, "environment", "部分环境设备没有可用读数")
                : Mono.just(AreaRead.empty());
        Mono<AreaRead> energyRead = includeEnergy
                ? readArea(identity, ObservationOperation.ENERGY_HISTORY, energy, "energy", "部分电表没有返回最新读数")
                : Mono.just(AreaRead.empty());
        Mono<AlertRead> alerts = includeAlerts
                ? readAlerts(identity, safety)
                : Mono.just(AlertRead.empty());
        Mono<AutomationRead> automation = includeAutomation
                ? readAutomation(identity)
                : Mono.just(AutomationRead.empty());

        return Mono.zip(environmentRead, energyRead, alerts, automation)
                .map(values -> dashboardPayload(section, environment, energy, safety,
                        values.getT1(), values.getT2(), values.getT3(), values.getT4(), identity));
    }

    private ObservationResponse dashboardPayload(
            String section,
            List<AuthorizedDevice> environment,
            List<AuthorizedDevice> energy,
            List<AuthorizedDevice> safety,
            AreaRead environmentRead,
            AreaRead energyRead,
            AlertRead alerts,
            AutomationRead automation,
            RequestIdentity identity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (section != null) payload.put("section", section);
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("devices", dashboardDevices(environment, energy, safety));
        List<Map<String, Object>> errors = new ArrayList<>();
        errors.addAll(environmentRead.errors);
        errors.addAll(energyRead.errors);
        errors.addAll(alerts.errors);
        errors.addAll(automation.errors);
        if (section == null || section.equals("summary") || section.equals("environment")) {
            payload.put("environment", readings(environment, environmentRead.values));
        }
        if (section == null || section.equals("energy")) {
            payload.put("energy", readings(energy, energyRead.values));
        }
        if (section == null || section.equals("summary") || section.equals("alerts")) {
            payload.put("alerts", Map.of("total", alerts.records.size(), "records", alerts.records));
        }
        if (section == null || section.equals("summary") || section.equals("automation")) {
            payload.put("automation", automation.value);
        }
        payload.put("errors", errors);
        return ObservationResponse.available(payload);
    }

    private Mono<AreaRead> readArea(
            RequestIdentity identity,
            ObservationOperation operation,
            List<AuthorizedDevice> devices,
            String area,
            String unavailableMessage) {
        if (devices.isEmpty()) return Mono.just(AreaRead.empty());
        ObservationRequest request = new ObservationRequest(identity, operation, Map.of());
        return upstream.read(request)
                .map(response -> areaResponse(response, devices, area, unavailableMessage))
                .onErrorResume(error -> Mono.just(AreaRead.failed(area, unavailableMessage)));
    }

    private AreaRead areaResponse(
            ObservationResponse response,
            List<AuthorizedDevice> devices,
            String area,
            String unavailableMessage) {
        Map<String, Object> values = extractReadings(response.payload(), devices);
        List<Map<String, Object>> errors = new ArrayList<>();
        if (response.status() != ObservationStatus.AVAILABLE) {
            errors.add(error(area, unavailableMessage));
        } else if (values.size() < devices.size()) {
            errors.add(error(area, unavailableMessage));
        }
        return new AreaRead(values, errors);
    }

    private Mono<AlertRead> readAlerts(RequestIdentity identity, List<AuthorizedDevice> safety) {
        if (safety.isEmpty()) return Mono.just(AlertRead.empty());
        return upstream.read(new ObservationRequest(identity, ObservationOperation.SAFETY_EVENTS, Map.of()))
                .map(response -> alertResponse(response, safety))
                .onErrorResume(error -> Mono.just(AlertRead.failed("消防告警台账读取失败")));
    }

    private AlertRead alertResponse(ObservationResponse response, List<AuthorizedDevice> safety) {
        List<Map<String, Object>> errors = response.status() == ObservationStatus.AVAILABLE
                ? List.of() : List.of(error("alerts", "消防告警台账读取失败"));
        Map<String, AuthorizedDevice> devices = new LinkedHashMap<>();
        safety.forEach(device -> devices.put(device.id(), device));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> source : extractRows(response.payload())) {
            String id = text(source, "deviceId", "id");
            AuthorizedDevice device = id == null ? null : devices.get(id);
            if (device == null) continue;
            Map<String, Object> record = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                if (!SetOfInternal.SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                    record.put(key, publicValue(value));
                }
            });
            record.putIfAbsent("deviceId", device.id());
            record.putIfAbsent("deviceName", device.name());
            record.putIfAbsent("recordedDeviceName", device.name());
            records.add(record);
        }
        return new AlertRead(records, errors);
    }

    private Mono<AutomationRead> readAutomation(RequestIdentity identity) {
        return automation.read(identity)
                .map(value -> new AutomationRead(value, List.of()))
                .onErrorResume(error -> Mono.just(AutomationRead.failed("任务摘要读取失败")));
    }

    private Mono<ObservationResponse> regionList(ObservationRequest request) {
        requireSource(request.identity(), IotSource.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        return officeContext(request.identity()).map(context -> {
            List<Map<String, Object>> regions = new ArrayList<>();
            for (Map<String, Object> region : context.regions) {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("id", region.get("id"));
                view.put("name", region.get("name"));
                if (regionSeen != null) view.put("attention", false);
                regions.add(view);
            }
            return ObservationResponse.available(Map.of("regions", regions));
        });
    }

    private Mono<ObservationResponse> regionDevices(ObservationRequest request) {
        requireSource(request.identity(), IotSource.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        String regionId = requiredRegionId(request);
        return officeContext(request.identity()).map(context -> {
            Map<String, Object> region = findRegion(context.regions, regionId)
                    .orElseThrow(() -> new BusinessException("REGION_NOT_FOUND", "区域不存在", 404));
            return ObservationResponse.available(Map.of("devices", deviceViews(devicesInRegion(context.scope.devices(), regionId))));
        });
    }

    private Mono<ObservationResponse> regionStates(ObservationRequest request) {
        requireSource(request.identity(), IotSource.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        String regionId = requiredRegionId(request);
        return officeContext(request.identity()).flatMap(context -> {
            Map<String, Object> region = findRegion(context.regions, regionId)
                    .orElseThrow(() -> new BusinessException("REGION_NOT_FOUND", "区域不存在", 404));
            List<AuthorizedDevice> devices = devicesInRegion(context.scope.devices(), regionId);
            List<String> ids = devices.stream().map(AuthorizedDevice::id).toList();
            if (ids.isEmpty()) return Mono.just(ObservationResponse.available(Map.of("states", List.of())));
            return iot.readDeviceStates(request.identity(), ids).map(read -> ObservationResponse.available(Map.of("states", stateViews(devices, read))));
        });
    }

    private Mono<ObservationResponse> regionSeen(ObservationRequest request) {
        requireSource(request.identity(), IotSource.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        if (regionSeen == null) return Mono.error(new BusinessException("REGION_SEEN_UNAVAILABLE", "区域已读存储暂不可用", 503));
        String regionId = requiredRegionId(request);
        return officeContext(request.identity()).flatMap(context -> {
            findRegion(context.regions, regionId).orElseThrow(() -> new BusinessException("REGION_NOT_FOUND", "区域不存在", 404));
            Instant at = clock.instant();
            return Mono.fromRunnable(() -> regionSeen.markSeen(request.identity().tenantCode(), request.identity().ownerKey(), regionId, at))
                    .thenReturn(ObservationResponse.available(Map.of("regionId", regionId, "seenAt", at.toString())));
        });
    }

    private Mono<OfficeContext> officeContext(RequestIdentity identity) {
        return iot.loadAuthorizedDeviceScope(identity)
                .flatMap(scope -> upstream.read(new ObservationRequest(identity, ObservationOperation.REGION_LIST, Map.of()))
                        .map(response -> {
                            if (response.status() != ObservationStatus.AVAILABLE) throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", REGION_UNAVAILABLE, 503);
                            return new OfficeContext(scope, parseRegions(response.payload()));
                        }));
    }

    private static List<Map<String, Object>> parseRegions(Map<String, Object> payload) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object raw = payload.get("regions");
        if (!(raw instanceof Collection<?> rows)) return result;
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) continue;
            String id = text(row, "id", "elementId");
            if (id == null || id.isBlank()) continue;
            String name = text(row, "name", "elementName");
            result.add(Map.of("id", id, "name", name == null || name.isBlank() ? "未命名区域" : name));
        }
        return result;
    }

    private static List<AuthorizedDevice> devicesInRegion(List<AuthorizedDevice> devices, String regionId) {
        return devices.stream().filter(device -> device.officeIds().contains(regionId)).toList();
    }

    private static List<Map<String, Object>> dashboardDevices(List<AuthorizedDevice>... groups) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<AuthorizedDevice> group : groups) for (AuthorizedDevice device : group) {
            result.add(Map.of("id", device.id(), "name", device.name(), "kind", device.kind(), "location", device.installAddress()));
        }
        return result;
    }

    private static List<Map<String, Object>> deviceViews(List<AuthorizedDevice> devices) {
        return devices.stream().map(device -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", device.id());
            view.put("name", device.name());
            view.put("kind", device.kind());
            view.put("location", device.installAddress());
            return view;
        }).toList();
    }

    private static List<Map<String, Object>> readings(List<AuthorizedDevice> devices, Map<String, Object> values) {
        return devices.stream().map(device -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", device.id());
            row.put("name", device.name());
            row.put("location", device.installAddress());
            row.put("reading", values.get(device.id()));
            return row;
        }).toList();
    }

    private static List<Map<String, Object>> stateViews(List<AuthorizedDevice> devices, DeviceStateReadResult read) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthorizedDevice device : devices) {
            Object raw = read.states().get(device.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", device.id());
            if (raw instanceof Map<?, ?> state && (state.containsKey("state") || state.containsKey("reportedAt"))) {
                row.put("state", state.get("state"));
                row.put("reportedAt", state.get("reportedAt"));
            } else {
                row.put("state", raw);
                row.put("reportedAt", null);
            }
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> extractReadings(Map<String, Object> payload, List<AuthorizedDevice> devices) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> row : extractRows(payload)) {
            String id = text(row, "deviceId", "equipmentId", "meterId", "id");
            if (id != null) rows.putIfAbsent(id, row);
        }
        for (AuthorizedDevice device : devices) {
            Map<String, Object> row = rows.get(device.id());
            if (row == null) continue;
            Object reading = row.containsKey("reading") ? publicValue(row.get("reading")) : publicValue(row);
            values.put(device.id(), reading);
        }
        return values;
    }

    private static List<Map<String, Object>> extractRows(Map<String, Object> payload) {
        for (String key : List.of("items", "readings", "records", "data", "content")) {
            Object raw = payload.get(key);
            if (raw instanceof Collection<?> rows) return rows.stream().filter(Map.class::isInstance).map(value -> castMap((Map<?, ?>) value)).toList();
        }
        List<Map<String, Object>> direct = new ArrayList<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> value) {
                Map<String, Object> row = castMap(value);
                row.putIfAbsent("deviceId", entry.getKey());
                direct.add(row);
            }
        }
        return direct;
    }

    private static Object publicValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                if (key != null && !SetOfInternal.SECRET_KEYS.contains(String.valueOf(key).toLowerCase(Locale.ROOT))) {
                    result.put(String.valueOf(key), publicValue(nested));
                }
            });
            return result;
        }
        if (value instanceof Collection<?> values) return values.stream().map(CompanyOfficeObservationAdapter::publicValue).toList();
        return value;
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (key != null) result.put(String.valueOf(key), value); });
        return result;
    }

    private static String text(Map<?, ?> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private static List<AuthorizedDevice> devices(AuthorizedDeviceScope scope, String kind) {
        return scope.devices().stream().filter(device -> kind.equalsIgnoreCase(device.kind())).toList();
    }

    private static String requiredRegionId(ObservationRequest request) {
        String regionId = request.parameters().get("regionId");
        if (regionId == null || regionId.isBlank()) throw new BusinessException("REGION_NOT_FOUND", "区域不存在", 404);
        return regionId;
    }

    private static Optional<Map<String, Object>> findRegion(List<Map<String, Object>> regions, String id) {
        return regions.stream().filter(region -> id.equals(region.get("id"))).findFirst();
    }

    private static void requireSource(RequestIdentity identity, IotSource expected, String code, String message) {
        if (identity.source() != expected) throw new BusinessException(code, message, 404);
    }

    private static String cacheKey(RequestIdentity identity, String section) {
        return identity.tenantCode() + "\u0000" + identity.ownerKey() + "\u0000" + (section == null ? "all" : section);
    }

    private void invalidateDashboard(String key) {
        dashboardCache.remove(key);
    }

    private static boolean isAuthorizationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof GatewayAuthorizationException) return true;
            if (current instanceof BusinessException business && (business.httpStatus() == 401 || business.httpStatus() == 403)) return true;
            current = current.getCause();
        }
        return false;
    }

    private static Map<String, Object> error(String area, String message) {
        return Map.of("area", area, "message", message);
    }

    private record CacheEntry(ObservationResponse response, long at) { }
    private record OfficeContext(AuthorizedDeviceScope scope, List<Map<String, Object>> regions) { }
    private record AreaRead(Map<String, Object> values, List<Map<String, Object>> errors) {
        static AreaRead empty() { return new AreaRead(Map.of(), List.of()); }
        static AreaRead failed(String area, String message) { return new AreaRead(Map.of(), List.of(error(area, message))); }
    }
    private record AlertRead(List<Map<String, Object>> records, List<Map<String, Object>> errors) {
        static AlertRead empty() { return new AlertRead(List.of(), List.of()); }
        static AlertRead failed(String message) { return new AlertRead(List.of(), List.of(error("alerts", message))); }
    }
    private record AutomationRead(Object value, List<Map<String, Object>> errors) {
        static AutomationRead empty() { return new AutomationRead(null, List.of()); }
        static AutomationRead failed(String message) { return new AutomationRead(null, List.of(error("automation", message))); }
    }

    private static final class SetOfInternal {
        private static final java.util.Set<String> SECRET_KEYS = java.util.Set.of(
                "authorization", "tenantcode", "token", "accesstoken", "refreshtoken", "apikey", "secret",
                "password", "providerid", "internalauthkey", "controlref", "hubdeviceid");
    }
}
