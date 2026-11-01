package ltd.qianc.iotagent.observation.internal.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.iot.VideoStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP parity shell for read-only Runtime routes. The controller validates source
 * and query shape, then delegates all upstream facts to the explicit adapter.
 */
@RestController
@RequestMapping("/api/agent")
public final class ObservationController {
    private static final Duration ADAPTER_TIMEOUT = Duration.ofSeconds(30);
    private final ObservationAdapter adapter;
    private final IotAdapter iotAdapter;

    public ObservationController(ObservationAdapter adapter) {
        this(adapter, null);
    }

    @Autowired
    public ObservationController(ObservationAdapter adapter, IotAdapter iotAdapter) {
        this.adapter = adapter;
        this.iotAdapter = iotAdapter;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(HttpServletRequest request) {
        ResponseEntity<?> source = requireCompanySource(request, "DASHBOARD_SOURCE_UNAVAILABLE", "仪表盘仅支持公司来源");
        if (source != null) return source;
        String section = request.getParameter("section");
        if (section != null && !List.of("summary", "energy", "environment", "alerts", "automation").contains(section)) {
            return error(400, "INVALID_DASHBOARD_SECTION", "概览分区无效");
        }
        return read(request, ObservationOperation.DASHBOARD, Map.of());
    }

    @GetMapping("/environment-history")
    public ResponseEntity<?> environmentHistory(HttpServletRequest request) {
        ResponseEntity<?> source = requireCompanySource(request, "HISTORY_SOURCE_UNAVAILABLE", "环境历史仅支持公司来源");
        if (source != null) return source;
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_HISTORY_WINDOW", "查询参数不可重复", "from", "to", "page");
        if (duplicate != null) return duplicate;
        return read(request, ObservationOperation.ENVIRONMENT_HISTORY, Map.of());
    }

    @GetMapping("/safety-events")
    public ResponseEntity<?> safetyEvents(HttpServletRequest request) {
        ResponseEntity<?> source = requireCompanySource(request, "SAFETY_SOURCE_UNAVAILABLE", "安全历史仅支持公司来源");
        if (source != null) return source;
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_SAFETY_QUERY", "查询参数不可重复", "type", "from", "to", "limit", "cursor", "page");
        if (duplicate != null) return duplicate;
        String type = valueOr(request, "type", "gas");
        String limit = valueOr(request, "limit", "20");
        if (request.getParameter("cursor") != null) {
            return error(503, "SAFETY_CURSOR_UNAVAILABLE", "安全台账继续分页暂不可用，请重新查询");
        }
        if (!List.of("gas", "smoke", "water").contains(type) || !integerBetween(limit, 1, 100)
                || !validSafetyWindow(request.getParameter("from"), request.getParameter("to"))) {
            return error(400, "INVALID_SAFETY_QUERY", "台账类别、分页数量或查询时间范围无效");
        }
        return read(request, ObservationOperation.SAFETY_EVENTS, Map.of());
    }

    @GetMapping("/safety-events/{id}")
    public ResponseEntity<?> safetyEvent(HttpServletRequest request, @PathVariable String id) {
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_REFERENCE", "安全台账记录身份不可重复", "ledger", "deviceId");
        if (duplicate != null) return duplicate;
        if (request.getParameter("ledger") == null || request.getParameter("deviceId") == null
                || !List.of("gas", "smoke", "flooding").contains(request.getParameter("ledger"))) {
            return error(400, "INVALID_REFERENCE", "安全台账记录身份无效");
        }
        return read(request, ObservationOperation.SAFETY_EVENT, Map.of("id", id));
    }
    @GetMapping("/safety-events/{id}/cameras")
    public ResponseEntity<?> safetyEventCameras(HttpServletRequest request, @PathVariable String id) {
        ResponseEntity<?> source = requireCompanySource(request, "SAFETY_SOURCE_UNAVAILABLE", "安全记录监控复盘仅支持公司来源");
        if (source != null) return source;
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_REFERENCE", "安全台账记录身份不可重复", "ledger", "deviceId");
        if (duplicate != null) return duplicate;
        if (request.getParameter("ledger") == null || request.getParameter("deviceId") == null
                || !List.of("gas", "smoke", "flooding").contains(request.getParameter("ledger"))) {
            return error(400, "INVALID_REFERENCE", "安全台账记录身份无效");
        }
        return read(request, ObservationOperation.SAFETY_EVENT_CAMERAS, Map.of("id", id));
    }

    @GetMapping("/regions")
    public ResponseEntity<?> regions(HttpServletRequest request) {
        ResponseEntity<?> source = requireOfficeOrDefault(request);
        if (source != null) return source;
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_REGION_SECTION", "查询参数不可重复", "source", "section");
        if (duplicate != null) return duplicate;
        String section = request.getParameter("section");
        if (section != null && !section.equals("directory")) return error(400, "INVALID_REGION_SECTION", "区域分区无效");
        return read(request, ObservationOperation.REGION_LIST, Map.of());
    }

    @PostMapping("/regions/{regionId}/seen")
    public ResponseEntity<?> regionSeen(HttpServletRequest request, @PathVariable String regionId) {
        ResponseEntity<?> source = requireOfficeOrDefault(request);
        if (source != null) return source;
        return read(request, ObservationOperation.REGION_SEEN, Map.of("regionId", regionId));
    }

    @GetMapping("/regions/{regionId}/devices")
    public ResponseEntity<?> regionDevices(HttpServletRequest request, @PathVariable String regionId) {
        ResponseEntity<?> source = requireOfficeOrDefault(request);
        if (source != null) return source;
        return read(request, ObservationOperation.REGION_DEVICES, Map.of("regionId", regionId));
    }

    @GetMapping("/regions/{regionId}/device-states")
    public ResponseEntity<?> regionStates(HttpServletRequest request, @PathVariable String regionId) {
        ResponseEntity<?> source = requireOfficeOrDefault(request);
        if (source != null) return source;
        return read(request, ObservationOperation.REGION_STATES, Map.of("regionId", regionId));
    }

    @GetMapping("/energy-history")
    public ResponseEntity<?> energyHistory(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.ENERGY_HISTORY);
    }

    @GetMapping("/energy-comparison")
    public ResponseEntity<?> energyComparison(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.ENERGY_COMPARISON);
    }

    @GetMapping("/energy-report")
    public ResponseEntity<?> energyReport(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.ENERGY_REPORT);
    }

    @GetMapping("/company-energy-daily")
    public ResponseEntity<?> companyEnergyDaily(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.COMPANY_ENERGY_DAILY);
    }

    @GetMapping("/company-energy-report")
    public ResponseEntity<?> companyEnergyReport(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.COMPANY_ENERGY_REPORT);
    }

    @GetMapping("/company-meter-report")
    public ResponseEntity<?> companyMeterReport(HttpServletRequest request) {
        return companyEnergyRead(request, ObservationOperation.COMPANY_METER_REPORT);
    }

    @GetMapping("/devices/{id}/video-stream")
    public ResponseEntity<?> videoStream(HttpServletRequest request, @PathVariable String id) {
        String mode = valueOr(request, "mode", "live");
        ResponseEntity<?> duplicate = rejectDuplicates(request, "INVALID_VIDEO_WINDOW", "查询参数不可重复",
                "source", "mode", "beginTime", "endTime");
        if (duplicate != null) return duplicate;
        String source = request.getParameter("source");
        if (!mode.equals("live") && !mode.equals("playback")) {
            return error(400, "INVALID_VIDEO_WINDOW", "请明确录像模式");
        }
        if (mode.equals("live")) {
            if (source == null) source = "office";
            if (request.getParameter("beginTime") != null || request.getParameter("endTime") != null) {
                return error(400, "INVALID_VIDEO_WINDOW", "直播模式不可带录像时间窗口");
            }
        } else {
            if (source == null || request.getParameter("beginTime") == null || request.getParameter("endTime") == null
                    || !validPlaybackWindow(request.getParameter("beginTime"), request.getParameter("endTime"))) {
                return error(400, "INVALID_VIDEO_WINDOW", "录像窗口须带时区，结束晚于开始，单次最多三天");
            }
        }
        if (!List.of("office", "company").contains(source)) {
            return error(404, "VIDEO_SOURCE_UNAVAILABLE", "视频来源不可用");
        }
        VideoPlaybackWindow playback = mode.equals("playback")
                ? normalizedPlaybackWindow(request.getParameter("beginTime"), request.getParameter("endTime")) : null;
        if (iotAdapter == null) return read(request, ObservationOperation.VIDEO_STREAM, Map.of("id", id));
        IotSource parsedSource = IotSource.parse(source);
        RequestIdentity identity = identity(request, parsedSource);
        try {
            VideoStream stream = playback != null
                    ? iotAdapter.openVideoPlayback(identity, id, playback).block(ADAPTER_TIMEOUT)
                    : iotAdapter.openVideoStream(identity, id).block(ADAPTER_TIMEOUT);
            if (stream == null) {
                String code = mode.equals("playback") ? "VIDEO_RECORDING_UNAVAILABLE" : "VIDEO_STREAM_UNAVAILABLE";
                String message = mode.equals("playback")
                        ? "所选设备或录像窗口未取得可播放流，请调整窗口或稍后重试"
                        : "视频设备不存在或暂时无法取流";
                throw new BusinessException(code, message, 404);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deviceId", id);
            body.put("name", stream.name());
            body.put("mode", mode);
            if (playback != null) {
                body.put("beginTime", playback.beginTime());
                body.put("endTime", playback.endTime());
            }
            body.put("url", stream.url());
            body.put("fetchedAt", Instant.now().toString());
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store, private").body(body);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ObservationUnavailableException("视频取流读取失败");
        }
    }


    private ResponseEntity<?> companyEnergyRead(HttpServletRequest request, ObservationOperation operation) {
        ResponseEntity<?> source = requireCompanySource(request, "ENERGY_SOURCE_UNAVAILABLE", "计量观察仅支持公司来源");
        if (source != null) return source;
        String duplicateCode = operation == ObservationOperation.ENERGY_HISTORY || operation == ObservationOperation.ENERGY_COMPARISON
                ? "INVALID_HISTORY_WINDOW" : "INVALID_ENERGY_WINDOW";
        ResponseEntity<?> duplicate = rejectDuplicates(request, duplicateCode, "查询参数不可重复",
                request.getParameterMap().keySet().toArray(String[]::new));
        if (duplicate != null) return duplicate;
        return read(request, operation, Map.of());
    }

    private ResponseEntity<?> read(HttpServletRequest request, ObservationOperation operation, Map<String, String> extras) {
        ObservationResponse result = readAdapter(new ObservationRequest(identity(request, sourceFor(request)), operation,
                parameters(request, operation, extras)));
        if (result.status() == ObservationStatus.UNAVAILABLE) throw new ObservationUnavailableException("只读观察读取失败");
        Map<String, Object> body = new LinkedHashMap<>(result.payload());
        if (result.status() != ObservationStatus.AVAILABLE && !body.containsKey("status")) {
            body.put("status", result.status().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!result.failures().isEmpty()) body.put("failures", result.failures());
        if (!result.coverageComplete()) body.put("coverageComplete", false);
        if (operation == ObservationOperation.VIDEO_STREAM) {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store, private").body(body);
        }
        return ResponseEntity.ok(body);
    }

    private ObservationResponse readAdapter(ObservationRequest request) {
        try {
            ObservationResponse result = adapter.read(request).block(ADAPTER_TIMEOUT);
            if (result == null) throw new ObservationUnavailableException("只读观察适配器返回空结果");
            return result;
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ObservationUnavailableException("只读观察读取失败");
        }
    }

    private RequestIdentity identity(HttpServletRequest request, IotSource source) {
        PrincipalInfo principal = PrincipalContext.getPrincipalInfo();
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source);
    }

    private IotSource sourceFor(HttpServletRequest request) {
        String source = request.getParameter("source");
        return IotSource.parse(source == null ? "office" : source);
    }

    private ResponseEntity<?> requireCompanySource(HttpServletRequest request, String code, String message) {
        String[] values = request.getParameterValues("source");
        if (values == null || values.length != 1 || !values[0].equals("company")) return error(404, code, message);
        return null;
    }

    private ResponseEntity<?> requireOfficeOrDefault(HttpServletRequest request) {
        String[] values = request.getParameterValues("source");
        if (values != null && (values.length != 1 || !values[0].equals("office"))) return error(404, "SOURCE_UNAVAILABLE", "区域仅支持办公室来源");
        return null;
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    private static ResponseEntity<?> rejectDuplicates(
            HttpServletRequest request, String code, String message, String... keys) {
        for (String key : keys) if (request.getParameterValues(key) != null && request.getParameterValues(key).length > 1) {
            return error(400, code, message);
        }
        return null;
    }

    private static String valueOr(HttpServletRequest request, String key, String fallback) {
        String value = request.getParameter(key);
        return value == null ? fallback : value;
    }

    private static boolean integerBetween(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean validSafetyWindow(String from, String to) {
        if (from == null && to == null) return true;
        if ((from != null && !zonedDateTime(from)) || (to != null && !zonedDateTime(to))) return false;
        return from == null || to == null || !OffsetDateTime.parse(from).isAfter(OffsetDateTime.parse(to));
    }

    private static boolean zonedDateTime(String value) {
        if (!value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")) {
            return false;
        }
        try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean validPlaybackWindow(String begin, String end) {
        if (begin == null || end == null || !begin.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")
                || !end.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")) return false;
        try {
            OffsetDateTime from = OffsetDateTime.parse(begin, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            OffsetDateTime to = OffsetDateTime.parse(end, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            long seconds = java.time.Duration.between(from, to).toSeconds();
            return seconds > 0 && seconds <= 3 * 24 * 60 * 60L;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static VideoPlaybackWindow normalizedPlaybackWindow(String begin, String end) {
        DateTimeFormatter runtimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        return new VideoPlaybackWindow(
                OffsetDateTime.parse(begin, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .withOffsetSameInstant(ZoneOffset.ofHours(8)).format(runtimeFormat),
                OffsetDateTime.parse(end, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .withOffsetSameInstant(ZoneOffset.ofHours(8)).format(runtimeFormat));
    }

    private static Map<String, String> parameters(
            HttpServletRequest request, ObservationOperation operation, Map<String, String> extras) {
        Set<String> allowed = switch (operation) {
            case DASHBOARD -> Set.of("section");
            case ENVIRONMENT_HISTORY -> Set.of("from", "to", "page");
            case SAFETY_EVENTS -> Set.of("type", "from", "to", "limit", "cursor", "page");
            case SAFETY_EVENT, SAFETY_EVENT_CAMERAS -> Set.of("ledger", "deviceId");
            case REGION_LIST -> Set.of("section");
            case REGION_TIMELINE, DEVICE_TIMELINE -> Set.of("date");
            case REGION_CALENDAR, DEVICE_CALENDAR -> Set.of("from", "to");
            case ENERGY_HISTORY -> Set.of("equipmentId", "from", "to", "page");
            case ENERGY_COMPARISON -> Set.of("equipmentId", "weeks", "endDate", "boundaryHour");
            case ENERGY_REPORT -> Set.of("kind", "from", "to", "equipmentIds", "baselineFrom", "baselineTo",
                    "periods", "growthThreshold");
            case COMPANY_ENERGY_DAILY -> Set.of("days", "end");
            case COMPANY_ENERGY_REPORT -> Set.of("months", "endMonth", "boundaryHour");
            case COMPANY_METER_REPORT -> Set.of("kind", "axis", "scopeLevel", "floor", "text", "meterId",
                    "meterIds", "from", "to", "windowFrom", "windowTo", "grain", "baseFrom", "baseTo",
                    "otherScopeLevel", "otherFloor", "otherText", "otherMeterId", "otherMeterIds",
                    "otherFrom", "otherTo");
            case VIDEO_STREAM -> Set.of("mode", "beginTime", "endTime");
            case REPORT_DOWNLOAD -> Set.of("format");
            case REGION_SEEN, REGION_DEVICES, REGION_STATES, REPORT -> Set.of();
        };
        Map<String, String> values = new LinkedHashMap<>();
        request.getParameterMap().forEach((key, all) -> {
            if (allowed.contains(key) && all != null && all.length > 0) values.put(key, all[0]);
        });
        values.putAll(extras);
        return values;
    }
}
