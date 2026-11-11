package ltd.qianc.iotagent.iot.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.AutomationSummaryReader;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposer;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.EnvironmentHistory;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatusEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.region.RegionSeenStore;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Local Runtime composition for the company dashboard and Office regions.
 *
 * <p>This adapter deliberately never calls the upstream DASHBOARD operation. It
 * obtains the current authorization catalog from {@link IotAdapter}, then reads
 * each observation area independently through the existing observation boundary.
 * Office regions and membership come from one local persisted directory batch;
 * observation collection and real control retain their separate boundaries.</p>
 */
public final class CompanyOfficeObservationAdapter implements ObservationAdapter {
    private static final Pattern FLOOR_PATTERN = Pattern.compile("(?:^|[^\\d])(\\d+)(?:楼|层)");

    private final ObservationAdapter upstream;
    private final IotAdapter iot;
    private final AutomationSummaryReader automation;
    private final RegionSeenStore regionSeen;
    private final CompanyEnergyObservationComposer energyComposer;
    private final ObservationAdapter safetyLedger;
    private final EnvironmentHistory environmentHistory;
    private final Clock clock;

    public CompanyOfficeObservationAdapter(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            EnvironmentHistory environmentHistory,
            Clock clock) {
        this(upstream, iot, automation, regionSeen, null, null, environmentHistory, clock);
    }

    public CompanyOfficeObservationAdapter(
            ObservationAdapter upstream,
            IotAdapter iot,
            AutomationSummaryReader automation,
            RegionSeenStore regionSeen,
            CompanyEnergyObservationComposer energyComposer,
            EnvironmentHistory environmentHistory,
            Clock clock) {
        this(upstream, iot, automation, regionSeen, energyComposer, null, environmentHistory, clock);
    }

    public CompanyOfficeObservationAdapter(
            ObservationAdapter upstream, IotAdapter iot, AutomationSummaryReader automation,
            RegionSeenStore regionSeen, CompanyEnergyObservationComposer energyComposer,
            ObservationAdapter safetyLedger, EnvironmentHistory environmentHistory, Clock clock) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.automation = Objects.requireNonNull(automation, "automation");
        this.regionSeen = regionSeen;
        this.energyComposer = energyComposer;
        this.safetyLedger = safetyLedger == null
                ? ltd.qianc.iotagent.iot.ObservationAdapterFactory.safetyLedger(upstream, iot, null, clock)
                : safetyLedger;
        this.environmentHistory = environmentHistory;
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
            case ENVIRONMENT_HISTORY -> composeEnvironment(request);
            case SAFETY_EVENTS, SAFETY_EVENT -> safetyLedger.read(request);
            case SAFETY_EVENT_CAMERAS -> safetyEventCameras(request);
            case ENERGY_HISTORY, COMPANY_ENERGY_DAILY, COMPANY_ENERGY_REPORT, ENERGY_COMPARISON, COMPANY_METER_REPORT, ENERGY_REPORT ->
                    composeEnergy(request);
            default -> upstream.read(request);
        };

    }


    private Mono<ObservationResponse> composeEnergy(ObservationRequest request) {
        if (energyComposer == null) return Mono.error(new BusinessException("ENERGY_HISTORY_UNAVAILABLE", "本地计量历史组合器未配置", 503));
        return request.operation() == ObservationOperationEnum.ENERGY_REPORT
                ? energyComposer.readEnergyReport(request)
                : energyComposer.read(request);
    }

    private Mono<ObservationResponse> composeEnvironment(ObservationRequest request) {
        if (environmentHistory == null) return Mono.error(environmentUnavailable());
        return Mono.fromCallable(() -> environmentHistory.read(request)).subscribeOn(Schedulers.boundedElastic());
    }

    private static BusinessException environmentUnavailable() {
        return new BusinessException("ENVIRONMENT_HISTORY_UNAVAILABLE", "本地环境读取未配置", 503);
    }

    private Mono<ObservationResponse> safetyEventCameras(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.COMPANY, "SAFETY_SOURCE_UNAVAILABLE",
                "安全记录监控复盘仅支持公司来源");
        String eventId = request.parameters().get("id");
        String ledger = request.parameters().get("ledger");
        String deviceId = request.parameters().get("deviceId");
        if (eventId == null || eventId.isBlank() || deviceId == null || deviceId.isBlank()
                || !List.of("gas", "smoke", "flooding").contains(ledger)) {
            return Mono.error(new BusinessException("INVALID_REFERENCE", "安全台账记录身份无效", 400));
        }
        Map<String, String> eventParameters = new LinkedHashMap<>(request.parameters());
        eventParameters.put("id", eventId);
        eventParameters.put("ledger", ledger);
        eventParameters.put("deviceId", deviceId);
        return safetyLedger.read(new ObservationRequest(
                request.identity(), ObservationOperationEnum.SAFETY_EVENT, eventParameters))
                .flatMap(response -> {
                    if (response.status() == ObservationStatusEnum.UNAVAILABLE) {
                        return Mono.error(new ltd.qianc.iotagent.iot.ObservationUnavailableException("安全台账读取失败"));
                    }
                    Object raw = response.payload().get("record");
                    if (!(raw instanceof Map<?, ?>)) {
                        return Mono.error(new BusinessException("SAFETY_EVENT_UNAVAILABLE",
                                "当前范围内未找到这条记录", 404));
                    }
                    Map<String, Object> record = castMap((Map<?, ?>) raw);
                    if (!eventId.equals(text(record, "eventId", "id"))
                            || !deviceId.equals(text(record, "deviceId"))
                            || !ledger.equals(text(record, "ledger"))) {
                        return Mono.error(new BusinessException("SAFETY_EVENT_UNAVAILABLE",
                                "当前范围内未找到这条记录", 404));
                    }
                    return iot.loadAuthorizedSafetyScope(request.identity())
                            .switchIfEmpty(Mono.error(new BusinessException("SAFETY_EVENT_READ_FAILED",
                                    "安全设备授权范围为空", 503)))
                            .map(scope -> checkedCompanyScope(scope, request.identity(), "安全设备授权范围无效"))
                            .flatMap(safety -> iot.loadAuthorizedVideoScope(request.identity())
                                    .switchIfEmpty(Mono.error(new BusinessException("SAFETY_EVENT_READ_FAILED",
                                            "监控授权范围为空", 503)))
                                    .map(scope -> checkedCompanyScope(scope, request.identity(), "监控授权范围无效"))
                                    .map(video -> safetyCameraPayload(request, record, safety, video)));
                });
    }

    private static AuthorizedDeviceScope checkedCompanyScope(
            AuthorizedDeviceScope scope, RequestIdentity identity, String message) {
        if (scope == null || scope.source() != IotSourceEnum.COMPANY || !identity.equals(scope.identity())) {
            throw new BusinessException("SAFETY_EVENT_READ_FAILED", message, 503);
        }
        return scope;
    }

    private ObservationResponse safetyCameraPayload(
            ObservationRequest request,
            Map<String, Object> sourceRecord,
            AuthorizedDeviceScope safety,
            AuthorizedDeviceScope video) {
        AuthorizedDevice current = safety.device(request.parameters().get("deviceId")).orElse(null);
        Map<String, Object> currentDevice = currentSafetyDevice(sourceRecord, current);
        Map<String, Object> record = new LinkedHashMap<>(sourceRecord);
        record.put("currentDevice", currentDevice);
        record.put("currentSafetyDevice", currentDevice);
        record.put("deviceName", text(currentDevice, "name") == null
                ? text(sourceRecord, "deviceName", "recordedDeviceName") : text(currentDevice, "name"));
        record.put("location", text(currentDevice, "location") == null ? "" : text(currentDevice, "location"));
        String occurredAt = text(record, "occurredAt");
        Instant occurred = parseInstant(occurredAt);
        String place = safetyPlace(text(currentDevice, "location"), text(currentDevice, "name"));
        List<AuthorizedDevice> cameras = video.devices().stream()
                .filter(device -> "video".equalsIgnoreCase(device.kind()) && device.hubDeviceId() != null)
                .toList();
        String floor = floorOf(place);
        List<AuthorizedDevice> samePlace = place.length() > 2
                ? cameras.stream().filter(camera -> normalized(camera.installAddress(), camera.name()).contains(normalized(place, "")))
                        .filter(camera -> floor == null || floor.equals(floorOf(cameraText(camera)))).toList()
                : List.of();
        List<AuthorizedDevice> matches = !samePlace.isEmpty() ? samePlace
                : floor == null ? List.of() : cameras.stream()
                        .filter(camera -> floor.equals(floorOf(cameraText(camera)))).toList();
        String basis = !samePlace.isEmpty()
                ? "按当前安装位置匹配“" + place + "”；仅为候选，记录时位置与画面覆盖未核验"
                : floor == null
                        ? "当前安装位置未包含可核验楼层；仅返回同地点候选，记录时位置与画面覆盖未核验"
                        : "按当前安装位置查找同楼层（" + floor + "楼）候选；记录时位置与画面覆盖未核验";
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (occurred != null) {
            // The candidate window is the recorded event's actual ±15 minutes. It is
            // never clipped to "now": a too-recent recording must fail playback
            // honestly instead of silently rewriting the referenced window.
            Instant end = occurred.plusSeconds(15 * 60L);
            if (end.isAfter(occurred)) {
                DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                        .withZone(ZoneOffset.ofHours(8));
                String beginTime = format.format(occurred.minusSeconds(15 * 60L));
                String endTime = format.format(end);
                String label = text(currentDevice, "name");
                if (label == null) label = text(record, "deviceName", "recordedDeviceName");
                for (AuthorizedDevice camera : matches) {
                    Map<String, Object> candidate = new LinkedHashMap<>();
                    candidate.put("source", "company");
                    candidate.put("deviceId", camera.id());
                    candidate.put("name", camera.name());
                    candidate.put("location", cameraLocation(camera));
                    candidate.put("beginTime", beginTime);
                    candidate.put("endTime", endTime);
                    candidate.put("safetyContext", Map.of(
                            "type", "safety_event", "ledger", request.parameters().get("ledger"),
                            "eventId", request.parameters().get("id"), "deviceId", request.parameters().get("deviceId"),
                            "label", label == null ? "" : label, "occurredAt", occurredAt, "basis", basis));
                    candidates.add(candidate);
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("record", record);
        payload.put("currentSafetyDevice", currentDevice);
        payload.put("candidates", candidates);
        payload.put("note", candidates.isEmpty()
                ? "当前Company目录中未找到同地点或同楼层候选监控；记录时位置未核验，不扩大到Office目录"
                : "按当前位置匹配，记录时位置未核验；可选直播或回放，默认记录前后各15分钟且不超过当前时间，录像是否存在以播放结果为准");
        return ObservationResponse.available(payload);
    }

    private static Map<String, Object> currentSafetyDevice(Map<String, Object> record, AuthorizedDevice current) {
        Map<String, Object> source = record.get("currentDevice") instanceof Map<?, ?> map
                ? castMap(map) : new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        String name = text(source, "name");
        String location = text(source, "location", "installAddress");
        if (name == null && current != null) name = current.name();
        // A blank scope address is an unverified location, not an empty one; keep it
        // null so candidate lookup cannot fabricate a place from missing metadata.
        if (location == null && current != null && !current.installAddress().isBlank()) {
            location = current.installAddress();
        }
        result.put("name", name);
        result.put("location", location);
        return result;
    }

    private static String safetyPlace(String location, String name) {
        String value = location == null ? "" : location;
        if (value.isBlank()) value = name == null ? "" : name;
        return value.replaceFirst("(燃气|光电感烟|烟感|水浸).*", "").replaceAll("\\s", "");
    }

    private static String floorOf(String value) {
        if (value == null) return null;
        Matcher matcher = FLOOR_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String cameraText(AuthorizedDevice camera) {
        String paths = camera.locationPaths().stream().map(path -> String.join(" ", path)).reduce("", (a, b) -> a + " " + b);
        return String.join(" ", camera.installAddress() == null ? "" : camera.installAddress(),
                camera.name() == null ? "" : camera.name(), paths);
    }

    private static String normalized(String left, String right) {
        return (left == null ? "" : left + right).replaceAll("\\s", "");
    }

    private static String cameraLocation(AuthorizedDevice camera) {
        if (camera.installAddress() != null && !camera.installAddress().isBlank()
                && !camera.installAddress().equals(camera.name())) return camera.installAddress();
        if (!camera.locationPaths().isEmpty()) return String.join(" / ", camera.locationPaths().get(0));
        return camera.installAddress() == null ? "" : camera.installAddress();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (RuntimeException ignoredAgain) {
                return null;
            }
        }
    }

    private Mono<ObservationResponse> dashboard(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.COMPANY, "DASHBOARD_SOURCE_UNAVAILABLE", "仪表盘仅支持公司来源");
        return iot.loadAuthorizedDeviceScope(request.identity()).flatMap(scope -> loadDashboard(request, scope));
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
                ? readObservationArea(identity, environment, "environment", "部分环境设备没有可用读数")
                : Mono.just(AreaRead.empty());
        Mono<AreaRead> energyRead = includeEnergy
                ? readObservationArea(identity, energy, "energy", "部分电表没有返回最新读数")
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
        payload.put("observedAt", stateObservedAt(environmentRead, energyRead));
        Map<String, Object> quality = new LinkedHashMap<>();
        if (section == null || section.equals("summary") || section.equals("environment"))
            quality.put("environment", stateMetadata(environmentRead.observation));
        if (section == null || section.equals("energy"))
            quality.put("energy", stateMetadata(energyRead.observation));
        if (!quality.isEmpty()) payload.put("stateQuality", quality);
        payload.put("devices", dashboardDevices(environment, energy, safety));
        List<Map<String, Object>> errors = new ArrayList<>();
        errors.addAll(environmentRead.errors);
        errors.addAll(energyRead.errors);
        errors.addAll(alerts.errors);
        errors.addAll(automation.errors);
        if (section == null || section.equals("summary") || section.equals("environment")) {
            payload.put("environment", readings(environment, environmentRead));
        }
        if (section == null || section.equals("energy")) {
            payload.put("energy", readings(energy, energyRead));
        }
        if (section == null || section.equals("summary") || section.equals("alerts")) {
            payload.put("alerts", Map.of("total", alerts.total, "records", alerts.records));
        }
        if (section == null || section.equals("summary") || section.equals("automation")) {
            payload.put("automation", automation.value);
        }
        payload.put("errors", errors);
        return ObservationResponse.available(payload);
    }

    private Mono<AreaRead> readObservationArea(
            RequestIdentity identity,
            List<AuthorizedDevice> devices,
            String area,
            String unavailableMessage) {
        if (devices.isEmpty()) return Mono.just(AreaRead.empty());
        Mono<DeviceStateReadResult> observation;
        if ("environment".equals(area)) {
            observation = environmentHistory == null ? Mono.error(environmentUnavailable())
                    : Mono.fromCallable(() -> environmentHistory.current(identity, devices.stream().map(AuthorizedDevice::id).toList()))
                            .subscribeOn(Schedulers.boundedElastic());
        } else observation = iot.readObservationStates(identity, devices);
        return observation
                .map(read -> observationAreaRead(read, devices, area, unavailableMessage))
                .onErrorResume(error -> isAuthorizationFailure(error) || error instanceof BusinessException business
                        && "DEVICE_STATE_PREPARING".equals(business.code())
                        ? Mono.error(error)
                        : Mono.just(AreaRead.failed(area, unavailableMessage, devices)));
    }

    private AreaRead observationAreaRead(
            DeviceStateReadResult read,
            List<AuthorizedDevice> devices,
            String area,
            String unavailableMessage) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (AuthorizedDevice device : devices) {
            Object state = read.states().get(device.id());
            if (state == null) continue;
            values.put(device.id(), publicValue(state));
        }
        if (values.size() < devices.size()) errors.add(error(area, unavailableMessage));
        return new AreaRead(values, errors, read);
    }

    private Mono<AlertRead> readAlerts(RequestIdentity identity, List<AuthorizedDevice> safety) {
        if (safety.isEmpty()) return Mono.just(AlertRead.empty());
        return reactor.core.publisher.Flux.fromIterable(List.of("gas", "smoke", "water"))
                .concatMap(type -> safetyLedger.read(new ObservationRequest(identity, ObservationOperationEnum.SAFETY_EVENTS, Map.of("type", type)))
                        .map(response -> alertResponse(response, safety)))
                .collectList()
                .map(parts -> new AlertRead(parts.stream().flatMap(part -> part.records.stream()).limit(50).toList(),
                        parts.stream().mapToInt(part -> part.total).sum(),
                        parts.stream().flatMap(part -> part.errors.stream()).toList()))
                .onErrorResume(error -> Mono.just(AlertRead.failed("消防告警台账读取失败")));
    }

    private AlertRead alertResponse(ObservationResponse response, List<AuthorizedDevice> safety) {
        List<Map<String, Object>> errors = response.status() == ObservationStatusEnum.AVAILABLE
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
        int total = response.payload().get("total") instanceof Number count ? count.intValue() : records.size();
        List<Map<String, Object>> visible = records.size() > 50 ? List.copyOf(records.subList(0, 50)) : List.copyOf(records);
        return new AlertRead(visible, total, errors);
    }

    private Mono<AutomationRead> readAutomation(RequestIdentity identity) {
        return automation.read(identity)
                .map(value -> new AutomationRead(value, List.of()))
                .onErrorResume(error -> Mono.just(AutomationRead.failed("任务摘要读取失败")));
    }

    private Mono<ObservationResponse> regionList(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        // Cacheable office names and live local task attention have separate lifetimes.
        // The HTTP composition adds attention after resolving this directory snapshot.
        return officeContext(request.identity())
                .map(context -> ObservationResponse.available(Map.of("regions", context.regions, "cache", context.cache)));
    }

    private Mono<ObservationResponse> regionDevices(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        String regionId = requiredRegionId(request);
        return officeContext(request.identity()).map(context -> {
            Map<String, Object> region = findRegion(context.regions, regionId)
                    .orElseThrow(() -> new BusinessException("REGION_NOT_FOUND", "区域不存在", 404));
            return ObservationResponse.available(Map.of("devices", deviceViews(devicesInRegion(context.scope.devices(), regionId)),
                    "cache", context.cache));
        });
    }

    private Mono<ObservationResponse> regionStates(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        String regionId = requiredRegionId(request);
        return officeContext(request.identity()).flatMap(context -> {
            Map<String, Object> region = findRegion(context.regions, regionId)
                    .orElseThrow(() -> new BusinessException("REGION_NOT_FOUND", "区域不存在", 404));
            List<AuthorizedDevice> devices = devicesInRegion(context.scope.devices(), regionId);
            List<String> ids = devices.stream().map(AuthorizedDevice::id).toList();
            if (ids.isEmpty()) return Mono.just(ObservationResponse.empty(Map.of("states", List.of())));
            return iot.readDeviceStates(request.identity(), ids).map(read -> {
                Map<String, Object> payload = stateMetadata(read);
                payload.put("states", stateViews(devices, read));
                return new ObservationResponse(read.status(), payload, List.of(),
                        read.status() == ObservationStatusEnum.AVAILABLE || read.status() == ObservationStatusEnum.EMPTY);
            });
        });
    }

    private Mono<ObservationResponse> regionSeen(ObservationRequest request) {
        requireSource(request.identity(), IotSourceEnum.OFFICE, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
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
        return iot.readDirectory(identity).map(batch ->
                new OfficeContext(batch.scope(), batch.regions().stream()
                        .map(region -> Map.<String, Object>of("id", region.id(), "name", region.name())).toList(), batch.cacheMetadata()));
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

    private static List<Map<String, Object>> readings(List<AuthorizedDevice> devices, AreaRead read) {
        return devices.stream().map(device -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("deviceId", device.id());
            row.put("name", device.name());
            row.put("location", device.installAddress());
            row.put("reading", read.values.get(device.id()));
            row.put("observedAt", read.observation.observedAtByDevice().getOrDefault(device.id(), read.observation.observedAt()));
            return row;
        }).toList();
    }

    private static Map<String, Object> stateMetadata(DeviceStateReadResult read) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", read.status().name().toLowerCase(java.util.Locale.ROOT));
        metadata.put("observedAt", read.observedAt());
        metadata.put("observedAtByDevice", read.observedAtByDevice());
        metadata.put("missingDeviceIds", read.missingDeviceIds());
        metadata.put("cache", read.cache());
        return metadata;
    }

    private static String stateObservedAt(AreaRead first, AreaRead second) {
        if (first.observation.status() == ObservationStatusEnum.EMPTY) return second.observation.observedAt();
        if (second.observation.status() == ObservationStatusEnum.EMPTY) return first.observation.observedAt();
        Instant a = parseInstant(first.observation.observedAt()), b = parseInstant(second.observation.observedAt());
        return a == null || b == null ? null : (a.isBefore(b) ? a : b).toString();
    }

    private static List<Map<String, Object>> stateViews(List<AuthorizedDevice> devices, DeviceStateReadResult read) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthorizedDevice device : devices) {
            Object raw = read.states().get(device.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", device.id());
            if (raw instanceof Map<?, ?> state && (state.containsKey("state") || state.containsKey("reportedAt"))) {
                row.put("state", state.get("state"));
            } else {
                row.put("state", raw);
            }
            row.put("reportedAt", read.observedAtByDevice().getOrDefault(device.id(), read.observedAt()));
            result.add(row);
        }
        return result;
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

    private static void requireSource(RequestIdentity identity, IotSourceEnum expected, String code, String message) {
        if (identity.source() != expected) throw new BusinessException(code, message, 404);
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

    private record OfficeContext(AuthorizedDeviceScope scope, List<Map<String, Object>> regions, Map<String, Object> cache) { }
    private record AreaRead(Map<String, Object> values, List<Map<String, Object>> errors, DeviceStateReadResult observation) {
        private static final AreaRead EMPTY = new AreaRead(Map.of(), List.of(), new DeviceStateReadResult(Map.of(), null));
        static AreaRead empty() { return EMPTY; }
        static AreaRead failed(String area, String message, List<AuthorizedDevice> devices) {
            Map<String, Object> states = new LinkedHashMap<>();
            for (AuthorizedDevice device : devices) states.put(device.id(), null);
            return new AreaRead(Map.of(), List.of(error(area, message)), new DeviceStateReadResult(
                    states, null, List.of(), Map.of(), ObservationStatusEnum.UNAVAILABLE, null, Map.of("status", "unavailable")));
        }
    }
    private record AlertRead(List<Map<String, Object>> records, int total, List<Map<String, Object>> errors) {
        static AlertRead empty() { return new AlertRead(List.of(), 0, List.of()); }
        static AlertRead failed(String message) { return new AlertRead(List.of(), 0, List.of(error("alerts", message))); }
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
