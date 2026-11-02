package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationGatewayRoutes;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.ObservationNotFoundException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Read-only customer gateway adapter. Routes are supplied explicitly; this class
 * never guesses an endpoint or turns a malformed response into an empty result.
 */
public final class WebClientObservationAdapter implements ObservationAdapter {
    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "tenantcode", "token", "accesstoken", "refreshtoken",
            "apikey", "secret", "password", "providerid", "internalauthkey");
    private static final Set<ObservationOperation> COMPANY_OPERATIONS = Set.of(
            ObservationOperation.DASHBOARD, ObservationOperation.ENVIRONMENT_HISTORY,
            ObservationOperation.SAFETY_EVENTS, ObservationOperation.SAFETY_EVENT,
            ObservationOperation.SAFETY_EVENT_CAMERAS, ObservationOperation.ENERGY_HISTORY,
            ObservationOperation.ENERGY_COMPARISON, ObservationOperation.ENERGY_REPORT,
            ObservationOperation.COMPANY_ENERGY_DAILY, ObservationOperation.COMPANY_ENERGY_REPORT,
            ObservationOperation.COMPANY_METER_REPORT, ObservationOperation.REPORT,
            ObservationOperation.REPORT_DOWNLOAD);
    private static final Set<ObservationOperation> OFFICE_OPERATIONS = Set.of(
            ObservationOperation.REGION_LIST, ObservationOperation.REGION_SEEN,
            ObservationOperation.REGION_DEVICES, ObservationOperation.REGION_STATES,
            ObservationOperation.REGION_TIMELINE, ObservationOperation.REGION_CALENDAR,
            ObservationOperation.DEVICE_TIMELINE, ObservationOperation.DEVICE_CALENDAR);

    private final WebClient webClient;
    private final ObservationGatewayRoutes routes;
    private final Duration callTimeout;
    private final IotCallPolicy callPolicy;

    public WebClientObservationAdapter(
            WebClient webClient,
            ObservationGatewayRoutes routes,
            Duration callTimeout,
            IotCallPolicy callPolicy) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.routes = Objects.requireNonNull(routes, "routes");
        if (callTimeout == null || callTimeout.isZero() || callTimeout.isNegative()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
        this.callTimeout = callTimeout;
        this.callPolicy = Objects.requireNonNull(callPolicy, "callPolicy");
    }

    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        validateSource(request.identity().source(), request.operation());
        String path = routes.resolvedPath(request.operation(), request.parameters());
        if (path == null) return Mono.error(unavailable(request.operation(), "客户观察路由未配置或参数无效"));
        Mono<ObservationResponse> call = webClient.get()
                .uri(builder -> {
                    var uri = builder.path(path);
                    queryParameters(request.operation(), request.parameters()).forEach((key, values) ->
                            values.forEach(value -> uri.queryParam(key, value)));
                    return uri.build();
                })
                .headers(headers -> request.identity().gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> decode(response, request.operation(), request.parameters()));
        return guarded(request.operation(), call);
    }

    @Override
    public Mono<ReportDownload> download(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != ObservationOperation.REPORT_DOWNLOAD) {
            return Mono.error(unavailable(request.operation(), "报告下载操作无效"));
        }
        validateSource(request.identity().source(), request.operation());
        String path = routes.resolvedPath(ObservationOperation.REPORT_DOWNLOAD, request.parameters());
        if (path == null) return Mono.error(unavailable(ObservationOperation.REPORT_DOWNLOAD, "客户报告下载路由未配置"));
        Mono<ReportDownload> call = webClient.get()
                .uri(builder -> {
                    var uri = builder.path(path);
                    queryParameters(request.operation(), request.parameters()).forEach((key, values) ->
                            values.forEach(value -> uri.queryParam(key, value)));
                    return uri.build();
                })
                .headers(headers -> request.identity().gatewayHeaders().forEach(headers::set))
                .exchangeToMono(this::decodeDownload);
        return guarded(ObservationOperation.REPORT_DOWNLOAD, call);
    }

    private Mono<ObservationResponse> decode(
            ClientResponse response, ObservationOperation operation, Map<String, String> parameters) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(unavailable(operation, "客户观察上游请求失败")));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(unavailable(operation, "客户观察上游响应为空")))
                .flatMap(body -> parseBody(body, operation, parameters));
    }

    private Mono<ObservationResponse> parseBody(
            JsonNode body, ObservationOperation operation, Map<String, String> parameters) {
        if (body == null || !body.isObject() || !body.path("code").isNumber()) {
            return Mono.error(unavailable(operation, "客户观察上游响应格式无效"));
        }
        int code = body.path("code").asInt();
        if (code == 401 || code == 403) return Mono.error(new GatewayAuthorizationException(code));
        if (code == 204) {
            if (operation == ObservationOperation.REGION_LIST) {
                return Mono.just(ObservationResponse.available(Map.of("regions", List.of())));
            }
            if (operation == ObservationOperation.SAFETY_EVENTS) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("source", "company");
                empty.put("items", List.of());
                empty.put("total", null);
                empty.put("excludedCount", 0);
                empty.put("scanned", 0);
                empty.put("coverageComplete", true);
                empty.put("nextCursor", null);
                return Mono.just(ObservationResponse.available(empty));
            }
            return Mono.just(ObservationResponse.empty(Map.of("status", "empty")));
        }
        if (code != 0 && code != 200) return Mono.error(unavailable(operation, "客户观察上游返回失败"));
        JsonNode content = body.get("content");
        if (content == null || content.isNull()) {
            return Mono.error(unavailable(operation, "客户观察上游内容格式无效"));
        }
        if (operation == ObservationOperation.REGION_LIST) {
            return parseRegions(content, operation);
        }
        if (operation == ObservationOperation.ENVIRONMENT_HISTORY) {
            return parseEnvironment(content, operation, parameters);
        }
        if (operation == ObservationOperation.ENERGY_HISTORY) {
            return parseEnergy(content, operation, parameters);
        }
        if (operation == ObservationOperation.SAFETY_EVENTS || operation == ObservationOperation.SAFETY_EVENT) {
            if (!content.isObject()) {
                return Mono.error(unavailable(operation, "安全台账上游内容格式无效"));
            }
            return parseSafety(content, operation, parameters);
        }
        if (!content.isObject()) {
            return Mono.error(unavailable(operation, "客户观察上游内容格式无效"));
        }
        Map<String, Object> payload = objectPayload(content);
        if (operation == ObservationOperation.REPORT && !knownReport(payload)) {
            return Mono.error(unavailable(operation, "客户报告响应模式未配置"));
        }
        if (operation == ObservationOperation.VIDEO_STREAM && !knownVideo(payload)) {
            return Mono.error(unavailable(operation, "客户视频响应模式未配置"));
        }
        return Mono.just(ObservationResponse.available(payload));
    }

    private Mono<ObservationResponse> parseSafety(
            JsonNode content, ObservationOperation operation, Map<String, String> parameters) {
        JsonNode rows = content.get("content");
        if (rows == null || !rows.isArray()) {
            return Mono.error(unavailable(operation, "安全台账上游分页格式无效"));
        }
        String ledger = parameters.get("ledger");
        if (ledger == null) {
            ledger = "water".equals(parameters.get("type")) ? "flooding" : parameters.getOrDefault("type", "gas");
        }
        String requestedId = parameters.get("id");
        String requestedDevice = parameters.get("deviceId");
        String from = parameters.get("from");
        String to = parameters.get("to");
        long fromMillis = from == null ? Long.MIN_VALUE : instantMillis(from);
        long toMillis = to == null ? Long.MAX_VALUE : instantMillis(to);
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = text(row, "id");
            String deviceId = text(row, "deviceId");
            String deviceName = text(row, "deviceName");
            String occurredAt = safetyTime(row.get("createdAt"));
            if (id == null || deviceId == null || occurredAt == null) continue;
            long occurredMillis = instantMillis(occurredAt);
            if (occurredMillis < fromMillis || occurredMillis > toMillis) continue;
            if (requestedDevice != null && !requestedDevice.equals(deviceId)) continue;
            if (requestedId != null && !requestedId.equals(id)) continue;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("eventId", id);
            record.put("ledger", ledger);
            record.put("deviceId", deviceId);
            record.put("deviceName", deviceName);
            record.put("recordedDeviceName", deviceName);
            Map<String, Object> currentDevice = new LinkedHashMap<>();
            currentDevice.put("name", null);
            currentDevice.put("location", null);
            record.put("currentDevice", currentDevice);
            record.put("location", "");
            record.put("subtype", "flooding".equals(ledger) ? "water" : ledger);
            record.put("occurredAt", occurredAt);
            record.put("alarm", row.has("alarm") && !row.get("alarm").isNull() ? row.get("alarm").asBoolean() : null);
            record.put("report", row.has("report") && !row.get("report").isNull() ? row.get("report").asBoolean() : null);
            records.add(record);
        }
        if (operation == ObservationOperation.SAFETY_EVENT) {
            if (records.isEmpty()) return Mono.error(new ObservationNotFoundException("当前授权范围内未找到安全台账记录"));
            return Mono.just(ObservationResponse.available(Map.of("source", "company", "record", records.get(0))));
        }
        int limit = parseLimit(parameters.get("limit"));
        if (records.size() > limit) records = new ArrayList<>(records.subList(0, limit));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("items", records);
        payload.put("window", Map.of("from", from == null ? "1970-01-01T00:00:00.000Z" : from,
                "to", to == null ? "9999-12-31T23:59:59.999Z" : to));
        payload.put("total", content.has("totalElements") ? content.get("totalElements").asInt() : null);
        payload.put("excludedCount", 0);
        payload.put("scanned", rows.size());
        payload.put("coverageComplete", content.path("last").asBoolean(rows.size() < 100));
        payload.put("nextCursor", null);
        return Mono.just(ObservationResponse.available(payload));
    }

    private Mono<ObservationResponse> parseEnvironment(JsonNode content, ObservationOperation op, Map<String,String> p) {
        JsonNode rows=pageRows(content); if(rows==null||!rows.isArray()) return Mono.error(unavailable(op,"环境历史上游分页格式无效"));
        List<Map<String,Object>> samples=new ArrayList<>(); long from=p.get("from")==null?Long.MIN_VALUE:instantMillis(p.get("from")); long to=p.get("to")==null?Long.MAX_VALUE:instantMillis(p.get("to"));
        for(JsonNode row:rows){String at=observationTime(row.get("createdAt")); if(at==null)return Mono.error(unavailable(op,"环境历史样本时间格式无效")); if(instantMillis(at)<from||instantMillis(at)>to)continue; String id=text(row,"id"); String device=text(row,"deviceCode"); if(id==null)id=at+":"+(device==null?"site":device); Map<String,Object> values=new LinkedHashMap<>(); values.put("temp",numberOrNull(row.get("tem"))); values.put("humidity",numberOrNull(row.get("hum"))); values.put("pm25",numberOrNull(row.get("pm25"))); values.put("pm10",numberOrNull(row.get("pm101"))); values.put("pm1",numberOrNull(row.get("pm10"))); Map<String,Object> sample=new LinkedHashMap<>(); sample.put("id",id);sample.put("recordedAt",at);sample.put("deviceCode",device);sample.put("values",values);samples.add(sample);}
        samples.sort(java.util.Comparator.comparing(x->String.valueOf(x.get("recordedAt")))); boolean complete=pageComplete(content,p); boolean last=pageLast(content,p); Map<String,Object> out=new LinkedHashMap<>(); out.put("source","company");out.put("timezone","Asia/Shanghai");out.put("attribution","site");out.put("observedAt",Instant.now().toString()); Map<String,Object> window=new LinkedHashMap<>();window.put("from",p.getOrDefault("from","1970-01-01T00:00:00.000Z"));window.put("to",p.getOrDefault("to","9999-12-31T23:59:59.999Z"));out.put("window",window);out.put("samples",samples);out.put("metrics",environmentMetrics(samples)); Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("complete",complete);coverage.put("readSamples",samples.size());coverage.put("from",samples.isEmpty()?null:samples.get(0).get("recordedAt"));coverage.put("to",samples.isEmpty()?null:samples.get(samples.size()-1).get("recordedAt"));out.put("coverage",coverage);out.put("nextPage",last?null:nextPage(p)); return last?Mono.just(ObservationResponse.available(out)):Mono.just(ObservationResponse.partial(out,List.of(new ltd.qianc.iotagent.iot.ObservationFailure("environment","HISTORY_PARTIAL","环境历史仅读取到当前页")),false));
    }
    private Mono<ObservationResponse> parseEnergy(JsonNode content, ObservationOperation op, Map<String, String> p) {
        JsonNode rows = pageRows(content);
        if (rows == null || !rows.isArray()) return Mono.error(unavailable(op, "计量历史上游分页格式无效"));
        String requested = p.getOrDefault("equipmentId", "");
        List<Map<String, Object>> samples = new ArrayList<>();
        long from = p.get("from") == null ? Long.MIN_VALUE : instantMillis(p.get("from"));
        long to = p.get("to") == null ? Long.MAX_VALUE : instantMillis(p.get("to"));
        for (JsonNode row : rows) {
            String meterId = text(row, "equipmentId");
            if (!requested.isBlank() && meterId != null && !requested.equals(meterId)) continue;
            if (meterId == null && requested.isBlank()) return Mono.error(unavailable(op, "计量历史缺少电表身份"));
            String at = observationTime(row.get("readTime"));
            if (at == null) return Mono.error(unavailable(op, "计量历史样本时间格式无效"));
            if (instantMillis(at) < from || instantMillis(at) > to) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("epf", numberOrNull(row.get("epf")));
            values.put("epz", numberOrNull(row.get("epz")));
            values.put("totalElectric", numberOrNull(row.get("totalElectric")));
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("id", text(row, "id") == null ? at : text(row, "id"));
            sample.put("equipmentId", meterId == null ? requested : meterId);
            sample.put("readTime", at);
            sample.put("values", values);
            samples.add(sample);
        }
        samples.sort(java.util.Comparator.comparing(sample -> String.valueOf(sample.get("readTime"))));
        Map<String, String> pagination = new LinkedHashMap<>(p);
        pagination.putIfAbsent("size", "20");
        boolean complete = pageComplete(content, pagination), last = pageLast(content, pagination);
        Map<String, Object> meter = new LinkedHashMap<>();
        meter.put("id", requested);
        meter.put("name", firstText(rows, "equipmentName", "name"));
        meter.put("location", firstText(rows, "areaName", "installAddress", "location"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "company");
        out.put("timezone", "Asia/Shanghai");
        out.put("observedAt", Instant.now().toString());
        out.put("meter", meter);
        out.put("window", Map.of("from", p.getOrDefault("from", "1970-01-01T00:00:00.000Z"),
                "to", p.getOrDefault("to", "9999-12-31T23:59:59.999Z")));
        out.put("fields", List.of(field("epf", "反向有功累计示数（epf）"),
                field("epz", "正向有功累计示数（epz）"), field("totalElectric", "平台总电量示数（totalElectric）")));
        out.put("multiplier", null);
        out.put("samples", samples);
        out.put("totalElements", content.path("totalElements").isIntegralNumber()
                ? content.path("totalElements").asLong() : null);
        out.put("coverage", Map.of("complete", complete, "returned", samples.size()));
        out.put("nextPage", last ? null : nextPage(p));
        Map<String, Object> latest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        out.put("latestReadTime", latest == null ? null : latest.get("readTime"));
        out.put("latestSample", latest);
        return last ? Mono.just(ObservationResponse.available(out))
                : Mono.just(ObservationResponse.partial(out,
                        List.of(new ltd.qianc.iotagent.iot.ObservationFailure("energy", "HISTORY_PARTIAL", "计量历史仅读取到当前页")), false));
    }
    private static Map<String,Object> field(String k,String label){Map<String,Object>x=new LinkedHashMap<>();x.put("key",k);x.put("label",label);x.put("unit",null);return x;}
    private static JsonNode pageRows(JsonNode c){if(c==null||c.isNull())return null;if(c.isArray())return c;JsonNode r=c.get("content");return r!=null&&r.isArray()?r:null;}
    private static boolean pageComplete(JsonNode content, Map<String, String> parameters) {
        return nextPage(parameters) == 1 && pageLast(content, parameters);
    }

    private static boolean pageLast(JsonNode content, Map<String, String> parameters) {
        if (content != null && content.has("last") && content.get("last").isBoolean()) return content.get("last").asBoolean();
        int size = 100;
        try { size = Math.max(1, Integer.parseInt(parameters.getOrDefault("size", "100"))); }
        catch (NumberFormatException ignored) { }
        if (content != null && content.has("totalElements") && content.get("totalElements").isIntegralNumber()) {
            return (long) nextPage(parameters) * size >= content.get("totalElements").asLong();
        }
        JsonNode rows = pageRows(content);
        return rows != null && rows.size() < size;
    }
    private static int nextPage(Map<String,String> p){try{return Math.max(0,Integer.parseInt(p.getOrDefault("page","0")))+1;}catch(NumberFormatException e){return 1;}}
    private static String firstText(JsonNode rows,String...keys){if(rows==null||!rows.isArray())return "";for(JsonNode row:rows){String v=text(row,keys);if(v!=null)return v;}return "";}
    private static List<Map<String,Object>> environmentMetrics(List<Map<String,Object>> samples){List<Map<String,Object>>x=new ArrayList<>();metric(samples,x,"temp","温度","°C");metric(samples,x,"humidity","湿度","%");metric(samples,x,"pm25","PM2.5",null);metric(samples,x,"pm10","PM10",null);metric(samples,x,"pm1","PM1.0",null);return x;}
    private static void metric(List<Map<String,Object>> samples,List<Map<String,Object>> out,String k,String label,String unit){List<Map<String,Object>>v=samples.stream().filter(x->((Map<?,?>)x.get("values")).get(k) instanceof Number).toList();Map<String,Object>m=new LinkedHashMap<>();m.put("key",k);m.put("label",label);m.put("unit",unit);m.put("count",v.size());if(v.isEmpty()){m.put("min",null);m.put("peak",null);}else{double min=v.stream().mapToDouble(x->((Number)((Map<?,?>)x.get("values")).get(k)).doubleValue()).min().orElseThrow();Map<String,Object>peak=v.stream().max(java.util.Comparator.comparingDouble(x->((Number)((Map<?,?>)x.get("values")).get(k)).doubleValue())).orElseThrow();m.put("min",min);m.put("peak",Map.of("value",((Map<?,?>)peak.get("values")).get(k),"at",peak.get("recordedAt")));}out.add(m);}
    private static Double numberOrNull(JsonNode v){if(v==null||v.isNull())return null;try{double n=v.isNumber()?v.asDouble():Double.parseDouble(v.asText().trim());return Double.isFinite(n)?n:null;}catch(RuntimeException e){return null;}}
    private static String observationTime(JsonNode v){if(v==null||v.isNull()||!v.isValueNode())return null;String raw=v.asText();try{return OffsetDateTime.parse(raw).toInstant().toString();}catch(RuntimeException e){try{return java.time.LocalDateTime.parse(raw.replace(' ','T'),DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.ofHours(8)).toString();}catch(RuntimeException x){return null;}}}

    private static int parseLimit(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "20" : value);
            return Math.max(1, Math.min(100, parsed));
        } catch (NumberFormatException ignored) {
            return 20;
        }
    }

    private static String safetyTime(JsonNode value) {
        if (value == null || value.isNull() || !value.isValueNode()) return null;
        String raw = value.asText();
        try {
            return OffsetDateTime.parse(raw).toInstant().toString();
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(raw.replace(' ', 'T') + "+08:00").toInstant().toString();
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    private static long instantMillis(String value) {
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant().toEpochMilli();
            } catch (RuntimeException invalid) {
                return Long.MIN_VALUE;
            }
        }
    }

    private Mono<ObservationResponse> parseRegions(JsonNode content, ObservationOperation operation) {
        JsonNode rows = content.isArray() ? content : content.get("content");
        if (rows == null || !rows.isArray()) {
            return Mono.error(unavailable(operation, "Office 区域响应格式无效"));
        }
        List<Map<String, Object>> regions = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = text(row, "elementId", "id");
            if (id == null || id.isBlank()) {
                return Mono.error(unavailable(operation, "Office 区域响应缺少区域标识"));
            }
            String name = text(row, "elementName", "name");
            regions.add(Map.of("id", id, "name", name == null || name.isBlank() ? "未命名区域" : name));
        }
        return Mono.just(ObservationResponse.available(Map.of("regions", regions)));
    }

    private Mono<ReportDownload> decodeDownload(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(unavailable(ObservationOperation.REPORT_DOWNLOAD, "客户报告下载失败")));
        }
        MediaTypeCheck media = MediaTypeCheck.from(response.headers().contentType().map(Object::toString).orElse(""));
        if (media.json()) {
            return response.releaseBody().then(Mono.error(unavailable(ObservationOperation.REPORT_DOWNLOAD, "客户报告下载响应模式未配置")));
        }
        return response.bodyToMono(byte[].class)
                .filter(bytes -> bytes.length > 0)
                .switchIfEmpty(Mono.error(unavailable(ObservationOperation.REPORT_DOWNLOAD, "客户报告下载内容为空")))
                .map(bytes -> new ReportDownload(media.value(), filename(response.headers().asHttpHeaders()), bytes));
    }

    private static Map<String, List<String>> queryParameters(
            ObservationOperation operation, Map<String, String> parameters) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        switch (operation) {
            case ENVIRONMENT_HISTORY -> {
                String from = parameters.get("from"), to = parameters.get("to");
                if (from != null) values.put("startDate", List.of(localDate(from)));
                if (to != null) values.put("endDate", List.of(localDate(to)));
                values.put("page", List.of(parameters.getOrDefault("page", "0")));
                values.put("size", List.of("100"));
                values.put("sort", List.of("createdAt,desc", "id,desc", "deviceCode,desc"));
            }
            case ENERGY_HISTORY -> {
                copyAllowed(values, parameters, "equipmentId", "size");
                String from = parameters.get("from"), to = parameters.get("to");
                if (from != null) values.put("startTime", List.of(localDateTime(from)));
                if (to != null) values.put("endTime", List.of(localDateTime(to)));
                values.put("page", List.of(parameters.getOrDefault("page", "0")));
                values.put("size", List.of(parameters.getOrDefault("size", "20")));
                values.put("sort", List.of("readTime,desc", "id,desc"));
            }
            case SAFETY_EVENTS, SAFETY_EVENT -> {
                String ledger = ledger(parameters.getOrDefault("type", parameters.getOrDefault("ledger", "gas")));
                values.put("page", List.of(parameters.getOrDefault("page", "0")));
                values.put("size", List.of("100"));
                copyAllowed(values, parameters, "deviceId", "id", "from", "to", "days", "unreportedOnly");
                values.put("sort", List.of("createdAt,desc", "id,desc", "deviceId,desc"));
                if (operation == ObservationOperation.SAFETY_EVENTS) values.put("ledger", List.of(ledger));
            }
            case REGION_TIMELINE, DEVICE_TIMELINE -> copyAllowed(values, parameters, "date");
            case REGION_CALENDAR, DEVICE_CALENDAR -> copyAllowed(values, parameters, "from", "to");
            case ENERGY_COMPARISON -> copyAllowed(values, parameters, "equipmentId", "weeks", "endDate", "boundaryHour");
            case ENERGY_REPORT -> copyAllowed(values, parameters, "kind", "from", "to", "equipmentIds",
                    "baselineFrom", "baselineTo", "periods", "growthThreshold");
            case COMPANY_ENERGY_DAILY -> copyAllowed(values, parameters, "days", "end");
            case COMPANY_ENERGY_REPORT -> copyAllowed(values, parameters, "months", "endMonth", "boundaryHour");
            case COMPANY_METER_REPORT -> copyAllowed(values, parameters, "kind", "axis", "scopeLevel", "floor",
                    "text", "meterId", "meterIds", "from", "to", "windowFrom", "windowTo", "grain", "baseFrom",
                    "baseTo", "otherScopeLevel", "otherFloor", "otherText", "otherMeterId", "otherMeterIds");
            case REPORT_DOWNLOAD -> copyAllowed(values, parameters, "format");
            case DASHBOARD, SAFETY_EVENT_CAMERAS, REGION_LIST, REGION_SEEN, REGION_DEVICES, REGION_STATES,
                    VIDEO_STREAM, REPORT -> {
                // These Runtime parameters select an internal route or response shape,
                // not a customer upstream query parameter.
            }
        }
        return values;
    }

    private static void copyAllowed(
            Map<String, List<String>> target, Map<String, String> parameters, String... keys) {
        for (String key : keys) {
            String value = parameters.get(key);
            if (value != null) target.put(key, List.of(value));
        }
    }

    private static String ledger(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "smoke" -> "smoke";
            case "water", "flooding" -> "flooding";
            case "gas" -> "gas";
            default -> throw new ObservationUnavailableException("SAFETY_EVENTS：安全台账类型无效");
        };
    }
    private static String localDate(String value) {
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDate().toString();
        } catch (RuntimeException invalid) {
            return value;
        }
    }

    private static String localDateTime(String value) {
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.ofHours(8))
                    .toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (RuntimeException invalid) {
            return value;
        }
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }

    private static Map<String, Object> objectPayload(JsonNode object) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!SECRET_KEYS.contains(normalize(field.getKey()))) result.put(field.getKey(), safeValue(field.getValue()));
        }
        return result;
    }

    private static Object safeValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) return objectPayload(node);
        if (node.isArray()) {
            ArrayList<Object> values = new ArrayList<>();
            node.forEach(item -> values.add(safeValue(item)));
            return values;
        }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber()) return node.longValue();
        if (node.isNumber()) return node.doubleValue();
        return node.asText();
    }

    private static boolean knownReport(Map<String, Object> payload) {
        Object report = payload.get("report");
        if (!(report instanceof Map<?, ?> value)) return false;
        return value.get("id") instanceof String && value.get("source") instanceof String
                && value.get("title") instanceof String && value.get("status") instanceof String;
    }

    private static boolean knownVideo(Map<String, Object> payload) {
        return payload.get("deviceId") instanceof String && payload.get("name") instanceof String
                && payload.get("mode") instanceof String && payload.get("url") instanceof String
                && !((String) payload.get("url")).isBlank();
    }

    private static String filename(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (value != null) {
            int start = value.indexOf("filename=\"");
            if (start >= 0) {
                int end = value.indexOf('"', start + 10);
                if (end > start) {
                    String candidate = value.substring(start + 10, end);
                    if (!candidate.isBlank() && candidate.indexOf('\r') < 0 && candidate.indexOf('\n') < 0) return candidate;
                }
            }
        }
        return "report";
    }

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static void validateSource(IotSource source, ObservationOperation operation) {
        if (COMPANY_OPERATIONS.contains(operation) && source != IotSource.COMPANY) {
            throw new ObservationUnavailableException("公司观察来源不匹配");
        }
        if (OFFICE_OPERATIONS.contains(operation) && source != IotSource.OFFICE) {
            throw new ObservationUnavailableException("办公室观察来源不匹配");
        }
    }

    private <T> Mono<T> guarded(ObservationOperation operation, Mono<T> call) {
        return callPolicy.execute("read observation " + operation.name(), call)
                .timeout(callTimeout)
                .onErrorMap(error -> error instanceof TimeoutException,
                        error -> unavailable(operation, "客户观察上游请求超时"))
                .onErrorMap(error -> !(error instanceof BusinessException)
                                && !(error instanceof CancellationException),
                        error -> unavailable(operation, "客户观察上游请求失败"));
    }

    private static ObservationUnavailableException unavailable(ObservationOperation operation, String reason) {
        return new ObservationUnavailableException(operation.name() + "：" + reason);
    }

    private record MediaTypeCheck(String value, boolean json) {
        static MediaTypeCheck from(String value) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return new MediaTypeCheck(value.isBlank() ? "application/octet-stream" : value,
                    normalized.contains("json") || normalized.startsWith("text/"));
        }
    }
}
