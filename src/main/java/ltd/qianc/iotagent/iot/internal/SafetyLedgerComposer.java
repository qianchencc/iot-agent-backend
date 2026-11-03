package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.ObservationNotFoundException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runtime-compatible safety ledger composition for Company and Office. Raw alarm
 * pages are read through the observation boundary (both sources read the same
 * fire-alarm ledger endpoint), filtered against the requesting source's current
 * authorized safety scope, and enriched with current device metadata read through
 * the same scope's hub binding. Event detail resolution and camera review remain
 * Company-only. Continuation uses the Runtime's opaque owner-bound cursor
 * (source, type, window, page, offset, before-key): a resumed scan can neither
 * repeat already returned records nor cross tenants, sources or query shapes.
 */
public final class SafetyLedgerComposer {
    /** Current device metadata source bound to the authorized safety scope's hub ids. */
    public interface MetadataSource {
        Mono<Map<String, Object>> currentDevice(RequestIdentity identity, String hubDeviceId);
    }

    private static final int PAGE_READS_PER_REQUEST = 3;
    private static final int DETAIL_PAGE_CAP = 5;
    private static final int METADATA_CONCURRENCY = 3;
    private static final int CURSOR_MAX_LENGTH = 2200;
    private static final int CURSOR_MAX_PAGE = 10000;
    private static final int CURSOR_MAX_OFFSET = 100;
    private static final int CURSOR_MAX_BEFORE = 700;
    private static final String DEFAULT_FROM = "1970-01-01T00:00:00.000Z";
    private static final Pattern ZONED_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$");
    private static final Set<String> LEDGERS = Set.of("gas", "smoke", "flooding");
    private static final Set<String> TYPES = Set.of("gas", "smoke", "water");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** Domain separation so the cursor MAC can never be confused with another signature. */
    private static final String CURSOR_MAC_DOMAIN = "iotagent:safety-ledger-cursor:v1";

    private final ObservationAdapter upstream;
    private final IotAdapter iot;
    private final MetadataSource metadata;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Base64.Encoder cursorEncoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder cursorDecoder = Base64.getUrlDecoder();
    private final byte[] cursorMacKey;

    public SafetyLedgerComposer(ObservationAdapter upstream, IotAdapter iot, MetadataSource metadata, Clock clock) {
        // Without a configured server secret, cursors are signed with an ephemeral
        // process key: integrity holds, but a restart invalidates them (honest 400,
        // the client re-queries). No credential is ever emitted inside the cursor.
        this(upstream, iot, metadata, ephemeralMacKey(), clock);
    }

    public SafetyLedgerComposer(
            ObservationAdapter upstream, IotAdapter iot, MetadataSource metadata,
            byte[] cursorMacKey, Clock clock) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.metadata = metadata;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cursorMacKey = cursorMacKey == null || cursorMacKey.length < 16
                ? ephemeralMacKey() : cursorMacKey.clone();
    }

    private static byte[] ephemeralMacKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /** SAFETY_EVENTS: filtered ledger page with honest coverage counters and continuation cursor. */
    public Mono<ObservationResponse> readLedger(ObservationRequest request) {
        requireLedgerSource(request.identity());
        Map<String, String> parameters = request.parameters();
        String type = parameters.getOrDefault("type", "gas");
        if (!TYPES.contains(type)) throw invalidQuery("台账类别或分页数量无效");
        int limit = parseLimit(parameters.get("limit"));
        String queryFrom = parameters.get("from");
        String queryTo = parameters.get("to");
        String from = queryFrom == null ? DEFAULT_FROM : queryFrom;
        String to = queryTo == null ? clock.instant().toString() : queryTo;
        CursorState startState = new CursorState(0, 0, "", from, to);
        String cursor = parameters.get("cursor");
        if (cursor != null && !cursor.isEmpty()) {
            startState = decodeCursor(cursor, request.identity(), type, queryFrom, queryTo);
        }
        // The cursor window wins over defaults; a query window must match it exactly.
        validateWindow(startState.from, startState.to);
        final CursorState state = startState;
        final String windowFrom = state.from;
        final String windowTo = state.to;
        long fromMillis = instantMillis(windowFrom);
        long toMillis = instantMillis(windowTo);
        String ledger = ledgerOf(type);
        RequestIdentity identity = request.identity();
        String source = identity.source().wireValue();
        return safetyScope(identity).flatMap(scope -> {
            Map<String, AuthorizedDevice> devices = safetyDevices(scope);
            if (devices.isEmpty()) {
                return Mono.just(ledgerResponse(source, List.of(), windowFrom, windowTo, 0, 0, 0, true, null));
            }
            LedgerScan scan = new LedgerScan(state.page, state.offset, state.before, type);
            return scanLedger(identity, ledger, fromMillis, toMillis, limit, devices, scan)
                    .flatMap(result -> enrich(identity, result.items, devices)
                            .map(currentByDevice -> ledgerResponse(
                                    source,
                                    ledgerItems(result.items, currentByDevice, type),
                                    windowFrom, windowTo, result.excluded, result.unknown, result.scanned, result.ended,
                                    result.ended ? null : encodeCursor(
                                            identity, type, windowFrom, windowTo, result.page, result.offset, result.before))));
        });
    }


    /** SAFETY_EVENT: durable identity resolution across bounded alarm pages of the referenced device. */
    public Mono<ObservationResponse> readEvent(ObservationRequest request) {
        requireCompany(request.identity(), "安全台账详情仅支持公司来源");
        Map<String, String> parameters = request.parameters();
        String eventId = trimToNull(parameters.get("id"));
        String deviceId = trimToNull(parameters.get("deviceId"));
        String ledger = parameters.get("ledger");
        if (eventId == null || eventId.length() > 300 || deviceId == null || deviceId.length() > 300
                || !LEDGERS.contains(ledger)) {
            throw new BusinessException("INVALID_REFERENCE", "安全台账记录身份无效", 400);
        }
        RequestIdentity identity = request.identity();
        return safetyScope(identity).flatMap(scope -> {
            Map<String, AuthorizedDevice> devices = safetyDevices(scope);
            AuthorizedDevice device = devices.get(deviceId);
            // Runtime resolves the referenced device in the current safety scope before
            // reading any alarm page; an out-of-scope reference is an honest miss.
            if (device == null) return Mono.error(notFound());
            return scanEvent(identity, ledger, deviceId, eventId, 0)
                    .flatMap(record -> currentDevice(identity, device)
                            .map(current -> eventResponse(facts(record, current, detailSubtype(record)))))
                    .switchIfEmpty(Mono.error(notFound()));
        });
    }


    private Mono<LedgerScan> scanLedger(
            RequestIdentity identity, String ledger, long fromMillis, long toMillis,
            int limit, Map<String, AuthorizedDevice> devices, LedgerScan scan) {
        if (scan.reads >= PAGE_READS_PER_REQUEST || scan.items.size() >= limit || scan.ended) {
            return Mono.just(scan);
        }
        return readPage(identity, ledger, scan.page, null).flatMap(page -> {
            scan.reads += 1;
            consumePage(page, scan, devices, fromMillis, toMillis, limit);
            return scanLedger(identity, ledger, fromMillis, toMillis, limit, devices, scan);
        });
    }

    private void consumePage(
            AlarmPage page, LedgerScan scan, Map<String, AuthorizedDevice> devices,
            long fromMillis, long toMillis, int limit) {
        List<AlarmRecord> records = page.records();
        while (scan.offset < records.size() && scan.items.size() < limit) {
            AlarmRecord record = records.get(scan.offset);
            scan.offset += 1;
            scan.scanned += 1;
            AuthorizedDevice device = devices.get(record.deviceId());
            String subtype = subtypeOf(device);
            if (device != null && subtype == null) {
                scan.unknown += 1;
                continue;
            }
            if (device == null || !scan.requestedType.equals(subtype)) {
                scan.excluded += 1;
                continue;
            }
            String key = fireAlarmKey(record);
            if (!scan.before.isEmpty() && key.compareTo(scan.before) >= 0) continue;
            Long occurred = instantMillisOrNull(record.occurredAt());
            if (occurred == null || occurred < fromMillis || occurred > toMillis) continue;
            scan.items.add(record);
            scan.before = key;
        }
        if (scan.offset >= records.size()) {
            scan.ended = page.last();
            scan.page += 1;
            scan.offset = 0;
        }
    }

    private Mono<AlarmRecord> scanEvent(RequestIdentity identity, String ledger, String deviceId, String eventId, int reads) {
        if (reads >= DETAIL_PAGE_CAP) return Mono.empty();
        return readPage(identity, ledger, reads, deviceId).flatMap(page -> {
            for (AlarmRecord record : page.records()) {
                if (eventId.equals(record.eventId()) && deviceId.equals(record.deviceId())) return Mono.just(record);
            }
            if (page.last() || reads + 1 >= DETAIL_PAGE_CAP) return Mono.empty();
            return scanEvent(identity, ledger, deviceId, eventId, reads + 1);
        });
    }

    private Mono<AlarmPage> readPage(RequestIdentity identity, String ledger, int page, String deviceId) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("ledger", ledger);
        parameters.put("page", String.valueOf(page));
        parameters.put("limit", "100");
        if (deviceId != null) parameters.put("deviceId", deviceId);
        return upstream.read(new ObservationRequest(identity, ObservationOperation.SAFETY_EVENTS, parameters))
                .flatMap(response -> {
                    if (response.status() != ObservationStatus.AVAILABLE
                            || !(response.payload().get("items") instanceof List<?> rows)) {
                        return Mono.error(unavailable("安全台账上游分页格式无效"));
                    }
                    List<AlarmRecord> records = new ArrayList<>();
                    for (Object row : rows) {
                        if (row instanceof Map<?, ?> value) {
                            AlarmRecord record = alarmRecord(value);
                            if (record != null) records.add(record);
                        }
                    }
                    records.sort((left, right) -> fireAlarmKey(right).compareTo(fireAlarmKey(left)));
                    boolean last = Boolean.TRUE.equals(response.payload().get("last"));
                    return Mono.just(new AlarmPage(records, last));
                });
    }

    private static AlarmRecord alarmRecord(Map<?, ?> row) {
        String eventId = text(row, "eventId", "id");
        String ledger = text(row, "ledger");
        String deviceId = text(row, "deviceId");
        String deviceName = text(row, "deviceName");
        String occurredAt = text(row, "occurredAt");
        // The Runtime page reader drops rows without a device name or a parseable time;
        // such rows never enter the scan and are not counted as scanned records.
        if (eventId == null || ledger == null || deviceId == null || deviceName == null || occurredAt == null) {
            return null;
        }
        return new AlarmRecord(eventId, ledger, deviceId, deviceName, occurredAt,
                booleanOrNull(row.get("alarm")), booleanOrNull(row.get("report")));
    }

    private Mono<Map<String, Map<String, Object>>> enrich(
            RequestIdentity identity, List<AlarmRecord> items, Map<String, AuthorizedDevice> devices) {
        List<String> deviceIds = items.stream().map(AlarmRecord::deviceId).distinct().toList();
        if (deviceIds.isEmpty()) return Mono.just(Map.of());
        return Flux.fromIterable(deviceIds)
                .flatMap(deviceId -> currentDevice(identity, devices.get(deviceId))
                        .map(value -> Map.entry(deviceId, value)), METADATA_CONCURRENCY)
                .collectList()
                .map(entries -> {
                    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
                    for (Map.Entry<String, Map<String, Object>> entry : entries) {
                        result.put(entry.getKey(), entry.getValue());
                    }
                    return result;
                });
    }

    private Mono<Map<String, Object>> currentDevice(RequestIdentity identity, AuthorizedDevice device) {
        if (metadata == null || device == null || device.hubDeviceId() == null || device.hubDeviceId().isBlank()) {
            return Mono.just(unknownDevice());
        }
        return metadata.currentDevice(identity, device.hubDeviceId())
                .onErrorResume(error -> error instanceof GatewayAuthorizationException
                        ? Mono.error(error)
                        : Mono.just(unknownDevice()));
    }

    private ObservationResponse ledgerResponse(
            String source, List<Map<String, Object>> items, String from, String to,
            int excluded, int unknown, int scanned, boolean coverageComplete, String nextCursor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("items", items);
        payload.put("observedAt", clock.instant().toString());
        payload.put("window", Map.of("from", from, "to", to));
        payload.put("total", null);
        payload.put("excludedCount", excluded);
        payload.put("unknownCount", unknown);
        payload.put("scanned", scanned);
        payload.put("coverageComplete", coverageComplete);
        payload.put("nextCursor", nextCursor);
        return ObservationResponse.available(payload);
    }

    private ObservationResponse eventResponse(Map<String, Object> record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("record", record);
        payload.put("observedAt", clock.instant().toString());
        return ObservationResponse.available(payload);
    }

    private static List<Map<String, Object>> ledgerItems(
            List<AlarmRecord> records, Map<String, Map<String, Object>> currentByDevice, String type) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmRecord record : records) {
            // Runtime records the requested ledger type on returned items; the
            // alarm row's own name-derived subtype stays on detail facts only.
            items.add(facts(record, currentByDevice.getOrDefault(record.deviceId(), unknownDevice()), type));
        }
        return items;
    }

    private static Map<String, Object> facts(AlarmRecord record, Map<String, Object> currentDevice, String subtype) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", record.eventId());
        item.put("ledger", record.ledger());
        item.put("deviceId", record.deviceId());
        item.put("deviceName", text(currentDevice, "name") == null ? record.deviceName() : text(currentDevice, "name"));
        item.put("recordedDeviceName", record.deviceName());
        item.put("currentDevice", currentDevice);
        item.put("location", text(currentDevice, "location") == null ? "" : text(currentDevice, "location"));
        item.put("subtype", subtype);
        item.put("occurredAt", record.occurredAt());
        item.put("alarm", record.alarm());
        item.put("report", record.report());
        return item;
    }

    /** Runtime detail subtype: the row's name classification, else the ledger default. */
    private static String detailSubtype(AlarmRecord record) {
        String byName = subtypeOfName(record.deviceName());
        return byName != null ? byName : ("flooding".equals(record.ledger()) ? "water" : record.ledger());
    }

    private static Map<String, Object> unknownDevice() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("name", null);
        unknown.put("location", null);
        return unknown;
    }

    /** Ledger pages follow the Runtime: Company and Office both read the fire-alarm
     *  ledger; every other source is rejected before any scope or page read. */
    private static void requireLedgerSource(RequestIdentity identity) {
        if (identity.source() != IotSource.COMPANY && identity.source() != IotSource.OFFICE) {
            throw new BusinessException("SAFETY_SOURCE_UNAVAILABLE", "安全历史仅支持公司或办公室来源", 404);
        }
    }

    private static void requireCompany(RequestIdentity identity, String message) {
        if (identity.source() != IotSource.COMPANY) {
            throw new BusinessException("SAFETY_SOURCE_UNAVAILABLE", message, 404);
        }
    }

    private Mono<AuthorizedDeviceScope> safetyScope(RequestIdentity identity) {
        return iot.loadAuthorizedSafetyScope(identity)
                .switchIfEmpty(Mono.error(new BusinessException("SAFETY_EVENT_READ_FAILED", "安全设备授权范围为空", 503)))
                .map(scope -> {
                    // Office falls back to its own device scope inside loadAuthorizedSafetyScope;
                    // the resolved scope must match the requesting source exactly so neither
                    // side can widen the other's safety catalog.
                    if (scope.source() != identity.source() || !identity.equals(scope.identity())) {
                        throw new BusinessException("SAFETY_EVENT_READ_FAILED", "安全设备授权范围无效", 503);
                    }
                    return scope;
                });
    }

    private static Map<String, AuthorizedDevice> safetyDevices(AuthorizedDeviceScope scope) {
        Map<String, AuthorizedDevice> devices = new LinkedHashMap<>();
        for (AuthorizedDevice device : scope.devices()) {
            if ("safety".equalsIgnoreCase(device.kind())) devices.putIfAbsent(device.id(), device);
        }
        return devices;
    }

    /** Runtime name classification: 烟 → smoke, 水浸 → water, 燃气 → gas, otherwise unverifiable. */
    private static String subtypeOf(AuthorizedDevice device) {
        return device == null ? null : subtypeOfName(device.name());
    }

    private static String subtypeOfName(String name) {
        if (name == null) return null;
        if (name.contains("烟")) return "smoke";
        if (name.contains("水浸")) return "water";
        if (name.contains("燃气")) return "gas";
        return null;
    }

    /**
     * Stable descending scan key: epoch-millis time first, then the Runtime's
     * numeric-id padding and device binding. Fixed-width time keeps the key order
     * monotonic with time regardless of fractional-second formatting upstream.
     */
    private static String fireAlarmKey(AlarmRecord record) {
        Long millis = instantMillisOrNull(record.occurredAt());
        String time = millis == null ? record.occurredAt() : String.format("%013d", millis);
        String id = record.eventId();
        String idPart = id.matches("\\d+") ? String.format("%06d", id.length()) + ":" + id : id;
        return time + ":" + idPart + ":" + record.deviceId();
    }

    private static String ledgerOf(String type) {
        return switch (type) {
            case "smoke" -> "smoke";
            case "water" -> "flooding";
            default -> "gas";
        };
    }

    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "20" : value);
            if (parsed < 1 || parsed > 100) throw invalidQuery("台账类别或分页数量无效");
            return parsed;
        } catch (NumberFormatException error) {
            throw invalidQuery("台账类别或分页数量无效");
        }
    }

    private static void validateWindow(String from, String to) {
        if (!zonedTime(from) || !zonedTime(to) || instantMillis(from) > instantMillis(to)) {
            throw invalidQuery("查询时间范围须包含明确时区");
        }
    }

    private static boolean zonedTime(String value) {
        if (value == null || !ZONED_TIME.matcher(value).matches()) return false;
        try {
            OffsetDateTime.parse(value, TIME_FORMAT);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long instantMillis(String value) {
        Long parsed = instantMillisOrNull(value);
        return parsed == null ? Long.MIN_VALUE : parsed;
    }

    private static Long instantMillisOrNull(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value, TIME_FORMAT).toInstant().toEpochMilli();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    private CursorState decodeCursor(
            String cursor, RequestIdentity identity, String type, String queryFrom, String queryTo) {
        if (cursor.length() > CURSOR_MAX_LENGTH) throw invalidCursor();
        // The MAC is verified before any field is trusted: a same-owner client can
        // decode and re-encode the JSON, but cannot produce a valid signature, so
        // edited page/offset/before/window values fail closed like any other forgery.
        int separator = cursor.lastIndexOf('.');
        if (separator <= 0 || separator == cursor.length() - 1) throw invalidCursor();
        String payload = cursor.substring(0, separator);
        String signature = cursor.substring(separator + 1);
        byte[] provided;
        try {
            provided = cursorDecoder.decode(signature);
        } catch (RuntimeException error) {
            throw invalidCursor();
        }
        if (!MessageDigest.isEqual(cursorMac(payload), provided)) throw invalidCursor();
        JsonNode node;
        try {
            node = mapper.readTree(cursorDecoder.decode(payload));
        } catch (RuntimeException | java.io.IOException error) {
            throw invalidCursor();
        }
        if (node == null || !node.isObject()) throw invalidCursor();
        String owner = text(node, "owner");
        String cursorSource = text(node, "source");
        String cursorType = text(node, "type");
        String from = text(node, "from");
        String to = text(node, "to");
        String before = text(node, "before");
        JsonNode pageNode = node.get("page");
        JsonNode offsetNode = node.get("offset");
        // The cursor also names its source: an Office continuation can never be
        // replayed under the Company authorization of the same owner, and cursors
        // minted before the source field existed fail closed like any forgery.
        if (!identity.ownerKey().equals(owner) || !type.equals(cursorType)
                || !identity.source().wireValue().equals(cursorSource)
                || from == null || to == null || before == null
                || (queryFrom != null && !queryFrom.equals(from))
                || (queryTo != null && !queryTo.equals(to))
                || pageNode == null || !pageNode.isIntegralNumber()
                || offsetNode == null || !offsetNode.isIntegralNumber()) {
            throw invalidCursor();
        }
        int page = pageNode.asInt();
        int offset = offsetNode.asInt();
        if (page < 0 || page > CURSOR_MAX_PAGE || offset < 0 || offset > CURSOR_MAX_OFFSET
                || before.length() > CURSOR_MAX_BEFORE) {
            throw invalidCursor();
        }
        return new CursorState(page, offset, before, from, to);
    }

    private String encodeCursor(
            RequestIdentity identity, String type, String from, String to, int page, int offset, String before) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("owner", identity.ownerKey());
        value.put("source", identity.source().wireValue());
        value.put("type", type);
        value.put("from", from);
        value.put("to", to);
        value.put("page", page);
        value.put("offset", offset);
        value.put("before", before);
        try {
            String payload = cursorEncoder.encodeToString(mapper.writeValueAsBytes(value));
            return payload + "." + cursorEncoder.encodeToString(
                    cursorMac((CURSOR_MAC_DOMAIN + ":" + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("安全台账游标编码失败", error);
        }
    }

    private byte[] cursorMac(String payload) {
        return cursorMac((CURSOR_MAC_DOMAIN + ":" + payload).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] cursorMac(byte[] signed) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorMacKey, "HmacSHA256"));
            return mac.doFinal(signed);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("安全台账游标签名失败", error);
        }
    }

    private static BusinessException invalidQuery(String message) {
        return new BusinessException("INVALID_SAFETY_QUERY", message, 400);
    }

    private static BusinessException invalidCursor() {
        return new BusinessException("INVALID_SAFETY_CURSOR", "分页已失效，请重新查询", 400);
    }

    private static ObservationNotFoundException notFound() {
        return new ObservationNotFoundException("当前授权与查询范围内未找到这条记录");
    }

    private static ObservationUnavailableException unavailable(String reason) {
        return new ObservationUnavailableException("SAFETY_EVENTS：" + reason);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof String text && !text.isBlank()) return text;
        }
        return null;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value != null && value.isTextual()) {
            String text = value.asText();
            if (!text.isEmpty()) return text;
        }
        return null;
    }

    private static Boolean booleanOrNull(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private record AlarmRecord(
            String eventId, String ledger, String deviceId, String deviceName,
            String occurredAt, Boolean alarm, Boolean report) {
    }

    private record AlarmPage(List<AlarmRecord> records, boolean last) {
    }

    private static final class CursorState {
        private final int page;
        private final int offset;
        private final String before;
        private final String from;
        private final String to;

        private CursorState(int page, int offset, String before) {
            this(page, offset, before, null, null);
        }

        private CursorState(int page, int offset, String before, String from, String to) {
            this.page = page;
            this.offset = offset;
            this.before = before;
            this.from = from;
            this.to = to;
        }
    }

    private static final class LedgerScan {
        private int page;
        private int offset;
        private String before;
        private boolean ended;
        private int reads;
        private int scanned;
        private int excluded;
        private int unknown;
        private final String requestedType;
        private final List<AlarmRecord> items = new ArrayList<>();

        private LedgerScan(int page, int offset, String before, String requestedType) {
            this.page = page;
            this.offset = offset;
            this.before = before;
            this.requestedType = requestedType;
        }
    }
}
