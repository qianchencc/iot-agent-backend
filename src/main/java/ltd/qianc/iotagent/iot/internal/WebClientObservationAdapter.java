package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
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
        String path = routes.path(request.operation());
        if (path == null) return Mono.error(unavailable(request.operation(), "客户观察路由未配置"));
        Mono<ObservationResponse> call = webClient.get()
                .uri(builder -> {
                    var uri = builder.path(path);
                    queryParameters(request.operation(), request.parameters()).forEach((key, values) ->
                            values.forEach(value -> uri.queryParam(key, value)));
                    return uri.build();
                })
                .headers(headers -> request.identity().gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> decode(response, request.operation()));
        return guarded(request.operation(), call);
    }

    @Override
    public Mono<ReportDownload> download(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != ObservationOperation.REPORT_DOWNLOAD) {
            return Mono.error(unavailable(request.operation(), "报告下载操作无效"));
        }
        validateSource(request.identity().source(), request.operation());
        String path = routes.path(ObservationOperation.REPORT_DOWNLOAD);
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

    private Mono<ObservationResponse> decode(ClientResponse response, ObservationOperation operation) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(unavailable(operation, "客户观察上游请求失败")));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(unavailable(operation, "客户观察上游响应为空")))
                .flatMap(body -> parseBody(body, operation));
    }

    private Mono<ObservationResponse> parseBody(JsonNode body, ObservationOperation operation) {
        if (body == null || !body.isObject() || !body.path("code").isNumber()) {
            return Mono.error(unavailable(operation, "客户观察上游响应格式无效"));
        }
        int code = body.path("code").asInt();
        if (code == 401 || code == 403) return Mono.error(new GatewayAuthorizationException(code));
        if (code == 204) return Mono.just(ObservationResponse.empty(Map.of("status", "empty")));
        if (code != 0 && code != 200) return Mono.error(unavailable(operation, "客户观察上游返回失败"));
        JsonNode content = body.get("content");
        if (content == null || !content.isObject()) {
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
        parameters.forEach((key, value) -> {
            if (value != null && !key.equals("source") && !key.equals("from") && !key.equals("to")) {
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        });
        if (operation == ObservationOperation.ENVIRONMENT_HISTORY) {
            String from = parameters.get("from"), to = parameters.get("to");
            if (from != null) values.put("startDate", List.of(localDate(from)));
            if (to != null) values.put("endDate", List.of(localDate(to)));
            values.putIfAbsent("page", List.of(parameters.getOrDefault("page", "0")));
            values.putIfAbsent("size", List.of("100"));
        } else if (operation == ObservationOperation.ENERGY_HISTORY) {
            String from = parameters.get("from"), to = parameters.get("to");
            if (from != null) values.put("startTime", List.of(localDateTime(from)));
            if (to != null) values.put("endTime", List.of(localDateTime(to)));
            values.putIfAbsent("page", List.of(parameters.getOrDefault("page", "0")));
            values.putIfAbsent("size", List.of("20"));
            values.computeIfAbsent("sort", ignored -> new ArrayList<>()).addAll(List.of("readTime,desc", "id,desc"));
        }
        return values;
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
