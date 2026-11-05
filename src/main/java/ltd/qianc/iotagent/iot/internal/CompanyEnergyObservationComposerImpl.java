package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposer;
import ltd.qianc.iotagent.iot.CompanyEnergyPolicy;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatusEnum;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Company energy projections with identity-scoped reusable history; upstream routes remain external. */
public final class CompanyEnergyObservationComposerImpl implements CompanyEnergyObservationComposer {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_MAX_PAGES = 60;
    private static final int DEFAULT_MAX_ROWS = 1_200;
    /** Meter fan-out is bounded so a large catalog cannot saturate the customer gateway. */
    private static final int METER_CONCURRENCY = 6;
    /** Wall-clock budget for one composed energy read; exhaustion is reported as truncation. */
    private static final long DEFAULT_READ_BUDGET_MS = 120_000L;
    /** Concurrent stream page fetches; the pages are independent and the window caps the count. */
    private static final int STREAM_PAGE_CONCURRENCY = 6;
    private static final int MAX_DAYS = 90;
    private static final long DAY_SECONDS = 86_400L;
    private static final long TWELVE_HOURS_SECONDS = 43_200L;

    private final ObservationAdapter rawHistory;
    private final IotAdapter iot;
    private final Clock clock;
    private final int maxPages;
    private final int maxRows;
    private final CompanyEnergyPolicy policy;
    private final EnergyHistoryStore history;
    private final ReadSnapshots historySnapshots;

    public CompanyEnergyObservationComposerImpl(ObservationAdapter rawHistory, IotAdapter iot, Clock clock) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, null);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, CompanyEnergyPolicy policy) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, policy);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, int maxPages, int maxRows) {
        this(rawHistory, iot, clock, maxPages, maxRows, null, null);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock,
            int maxPages, int maxRows, CompanyEnergyPolicy policy) {
        this(rawHistory, iot, clock, maxPages, maxRows, policy, null);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock,
            CompanyEnergyPolicy policy, ReadSnapshots historySnapshots) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, policy, historySnapshots);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, ReadSnapshots historySnapshots) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, null, historySnapshots);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock,
            int maxPages, int maxRows, CompanyEnergyPolicy policy, ReadSnapshots historySnapshots) {
        this.rawHistory = Objects.requireNonNull(rawHistory, "rawHistory");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPages < 1 || maxPages > 1_000) throw new IllegalArgumentException("maxPages must be 1..1000");
        if (maxRows < 1 || maxRows > 100_000) throw new IllegalArgumentException("maxRows must be 1..100000");
        this.maxPages = maxPages;
        this.maxRows = maxRows;
        this.policy = policy;
        this.history = new EnergyHistoryStore(clock);
        this.historySnapshots = historySnapshots;
    }

    @Override
    public Mono<ObservationResponse> readEnergyReport(ObservationRequest request) {
        return Mono.defer(() -> {
            requireCompany(request.identity());
            if (request.operation() != ObservationOperationEnum.ENERGY_REPORT) {
                return Mono.error(new ObservationUnavailableException("公司能耗组合器不支持该观察操作"));
            }
            Map<String, String> p = request.parameters();
            String kind = p.get("kind");
            if (!List.of("statistics", "comparison", "inspection").contains(kind)) {
                return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "请选择统计、对比或检查", 400));
            }
            List<ReportWindow> windows = reportWindows(p, kind);
            List<AuthorizedDevice> meters;
            return authorizedScope(request.identity())
                    .switchIfEmpty(Mono.error(new ObservationUnavailableException("公司电表授权范围为空")))
                    .flatMap(scope -> {
                        validateScope(request.identity(), scope);
                        List<AuthorizedDevice> all = energyMeters(scope);
                        List<AuthorizedDevice> selected = reportMeters(p.get("equipmentIds"), all);
                        if (selected.isEmpty()) {
                            return Mono.error(new BusinessException("FORBIDDEN", "统计范围中的计量点须属于当前公司授权目录，且不得重复", 403));
                        }
                        Instant from = windows.stream().map(ReportWindow::from).min(Instant::compareTo).orElseThrow();
                        Instant to = windows.stream().map(ReportWindow::to).max(Instant::compareTo).orElseThrow();
                        return readMeterHistory(request.identity(), all, selected, from, to, true)
                                .map(reads -> energyResponse(energyBusinessPayload(
                                        request, selected, reads, windows, kind, p)));
                    });
        });
    }

    private List<ReportWindow> reportWindows(Map<String, String> parameters, String kind) {
        if (parameters.containsKey("growthThreshold") && !"inspection".equals(kind)) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "用电增长检查需要明确的非负百分比阈值", 400);
        }
        Instant now = clock.instant();
        List<ReportWindow> explicit = new ArrayList<>();
        String encoded = parameters.get("periods");
        if (encoded != null) {
            try {
                List<Map<String, Object>> values = new ObjectMapper().readValue(encoded, new TypeReference<>() { });
                if (!"comparison".equals(kind) || parameters.containsKey("baselineFrom")
                        || parameters.containsKey("baselineTo") || values.size() < 2 || values.size() > 24) {
                    throw new IllegalArgumentException();
                }
                for (Map<String, Object> value : values) {
                    Instant periodFrom = strictInstant(String.valueOf(value.get("from")), now);
                    Instant periodTo = strictInstant(String.valueOf(value.get("to")), now);
                    if (!periodFrom.isBefore(periodTo)) throw new BusinessException(
                            "INVALID_ENERGY_WINDOW", "结束须晚于开始且不晚于当前时刻", 400);
                    String label = value.get("label") == null ? null : String.valueOf(value.get("label"));
                    explicit.add(new ReportWindow(periodFrom, periodTo, label == null ? null : label.substring(0, Math.min(60, label.length()))));
                }
            } catch (Exception error) {
                throw new BusinessException("INVALID_ENERGY_WINDOW", "明确对比支持2至24段，不能同时指定基期", 400);
            }
        }
        if (!explicit.isEmpty()) return explicit;
        Instant from = strictInstant(parameters.get("from"), now);
        Instant to = strictInstant(parameters.get("to"), now);
        if (!from.isBefore(to)) throw new BusinessException("INVALID_ENERGY_WINDOW", "结束须晚于开始且不晚于当前时刻", 400);
        if ("statistics".equals(kind) && (parameters.containsKey("baselineFrom") || parameters.containsKey("baselineTo"))) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "统计不需要基期", 400);
        }
        boolean baselineNeeded = "comparison".equals(kind) || ("inspection".equals(kind) && parameters.containsKey("growthThreshold"));
        List<ReportWindow> result = new ArrayList<>();
        if (baselineNeeded) {
            Instant baseFrom = parameters.containsKey("baselineFrom")
                    ? strictInstant(parameters.get("baselineFrom"), now) : from.minus(to.toEpochMilli() - from.toEpochMilli(), ChronoUnit.MILLIS);
            Instant baseTo = parameters.containsKey("baselineTo") ? strictInstant(parameters.get("baselineTo"), now) : from;
            if (!baseFrom.isBefore(baseTo)) throw new BusinessException(
                    "INVALID_ENERGY_WINDOW", "结束须晚于开始且不晚于当前时刻", 400);
            result.add(new ReportWindow(baseFrom, baseTo, null));
        }
        result.add(new ReportWindow(from, to, null));
        if (parameters.containsKey("growthThreshold")) {
            try {
                double threshold = Double.parseDouble(parameters.get("growthThreshold"));
                if (!Double.isFinite(threshold) || threshold < 0 || !"inspection".equals(kind)) throw new NumberFormatException();
            } catch (NumberFormatException error) {
                throw new BusinessException("INVALID_ENERGY_WINDOW", "用电增长检查需要明确的非负百分比阈值", 400);
            }
        }
        return result;
    }

    private Instant strictInstant(String value, Instant now) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "起止时间须为有效的带时区时刻", 400);
        }
        try {
            Instant parsed = OffsetDateTime.parse(value).toInstant();
            if (!parsed.isBefore(now)) throw new BusinessException("INVALID_ENERGY_WINDOW", "结束须晚于开始且不晚于当前时刻", 400);
            return parsed;
        } catch (DateTimeParseException error) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "起止时间须为有效的带时区时刻", 400);
        }
    }

    private List<AuthorizedDevice> reportMeters(String encoded, List<AuthorizedDevice> all) {
        if (encoded == null) return all;
        if (encoded.isBlank()) return List.of();
        Set<String> ids = new LinkedHashSet<>(List.of(encoded.split(",")));
        if (ids.size() != encoded.split(",").length) return List.of();
        if (ids.stream().anyMatch(id -> all.stream().noneMatch(m -> m.id().equals(id)))) return List.of();
        return all.stream().filter(m -> ids.contains(m.id())).toList();
    }

    private static void applyPolicy(Map<String, Object> period, CompanyEnergyPolicy.Meter configured) {
        if (configured == null || configured.multiplier() == null) return;
        Object usage = period.get("usage");
        if (usage instanceof Number number) period.put("usage", round(number.doubleValue() * configured.multiplier()));
    }

    private Map<String, Object> reportSeries(
            MeterRead read, CompanyEnergyPolicy.Meter configured, Instant from, Instant to) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String grain : List.of("day", "week", "month")) {
            List<Map<String, Object>> points = new ArrayList<>();
            for (ReportWindow bucket : bucketWindows(from, to, grain)) {
                Map<String, Object> point = new LinkedHashMap<>(comparisonPeriod(
                        read.samples, bucket.from(), bucket.to(), read.truncated, format(bucket.from()).substring(0, 10)));
                applyPolicy(point, configured);
                point.put("from", format(bucket.from())); point.put("to", format(bucket.to()));
                point.put("key", format(bucket.from()).substring(0, 10));
                point.put("usage", "comparable".equals(point.get("status")) ? point.get("usage") : null);
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("key", point.get("key")); output.put("from", point.get("from")); output.put("to", point.get("to"));
                output.put("usage", point.get("usage")); output.put("status", point.get("status")); points.add(output);
            }
            result.put(grain, points);
        }
        return result;
    }

    private Map<String, Object> reportSeries(
            List<Map<String, Object>> rows, Instant from, Instant to) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String grain : List.of("day", "week", "month")) {
            List<ReportWindow> buckets = bucketWindows(from, to, grain);
            List<Map<String, Object>> points = new ArrayList<>();
            for (int index = 0; index < buckets.size(); index++) {
                ReportWindow bucket = buckets.get(index);
                double sum = 0; int count = 0; String status = "not_computable";
                for (Map<String, Object> row : rows) {
                    @SuppressWarnings("unchecked") Map<String, Object> series = (Map<String, Object>) row.get("series");
                    @SuppressWarnings("unchecked") List<Map<String, Object>> values = (List<Map<String, Object>>) series.get(grain);
                    if (index < values.size() && "comparable".equals(values.get(index).get("status"))
                            && values.get(index).get("usage") instanceof Number value) {
                        sum += value.doubleValue(); count++;
                    }
                }
                if (count == rows.size() && count > 0) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> periods = (List<Map<String, Object>>) rows.get(0).get("periods");
                    status = !periods.isEmpty() && "comparable".equals(periods.get(periods.size() - 1).get("status"))
                            ? "comparable" : "partial";
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("key", format(bucket.from()).substring(0, 10)); point.put("from", format(bucket.from()));
                point.put("to", format(bucket.to())); point.put("usage", count == rows.size() && count > 0 ? round(sum) : null);
                point.put("status", status); points.add(point);
            }
            result.put(grain, points);
        }
        return result;
    }

    private List<ReportWindow> bucketWindows(Instant from, Instant to, String grain) {
        List<ReportWindow> result = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(to) && result.size() < 400) {
            Instant next;
            if ("month".equals(grain)) {
                var local = cursor.atZone(SHANGHAI);
                next = local.withDayOfMonth(1).plusMonths(1).toInstant();
            } else next = cursor.plus("week".equals(grain) ? 7 : 1, ChronoUnit.DAYS);
            result.add(new ReportWindow(cursor, next.isAfter(to) ? to : next, null));
            cursor = next;
        }
        return result;
    }

    private Map<String, Object> seriesCoverage(Instant from, Instant to) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String grain : List.of("day", "week", "month")) {
            List<ReportWindow> buckets = bucketWindows(from, to, grain);
            ReportWindow last = buckets.get(buckets.size() - 1);
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("complete", last.to().equals(to));
            coverage.put("remaining", last.to().equals(to) ? null
                    : Map.of("from", format(last.to()), "to", format(to)));
            result.put(grain, coverage);
        }
        return result;
    }

    private static Double difference(Map<String, Object> current, Map<String, Object> previous) {
        return current != null && previous != null ? round(((Number) current.get("value")).doubleValue() - ((Number) previous.get("value")).doubleValue()) : null;
    }

    private static Double ratio(Map<String, Object> current, Map<String, Object> previous, ReportWindow before, ReportWindow after) {
        return current != null && previous != null && ((Number) previous.get("value")).doubleValue() != 0
                && after.to().toEpochMilli() - after.from().toEpochMilli() == before.to().toEpochMilli() - before.from().toEpochMilli()
                ? round((((Number) current.get("value")).doubleValue() - ((Number) previous.get("value")).doubleValue())
                        / ((Number) previous.get("value")).doubleValue() * 100) : null;
    }

    private static String ratioNote(Map<String, Object> current, Map<String, Object> previous, ReportWindow before, ReportWindow after) {
        if (before.to().toEpochMilli() - before.from().toEpochMilli() != after.to().toEpochMilli() - after.from().toEpochMilli()) return "两期时长不同";
        if (previous == null || current == null) return "两期没有同口径的有效合计";
        if (((Number) previous.get("value")).doubleValue() == 0) return "基期为零";
        return "";
    }

    private static void annotateRatio(
            Map<String, Object> previous, Map<String, Object> current, ReportWindow before, ReportWindow after) {
        Object beforeValue = previous.get("usage"), currentValue = current.get("usage");
        if (!(beforeValue instanceof Number base) || !(currentValue instanceof Number value)) {
            current.put("ratio", null); current.put("ratioNote", "两期采样不完整，比例不可计算"); return;
        }
        long beforeDuration = before.to().toEpochMilli() - before.from().toEpochMilli();
        long afterDuration = after.to().toEpochMilli() - after.from().toEpochMilli();
        if (beforeDuration != afterDuration) {
            current.put("ratio", null); current.put("ratioNote", "两期时长不同，不直接计算增幅"); return;
        }
        if (base.doubleValue() == 0) {
            current.put("ratio", null); current.put("ratioNote", "基期为零，比例不可计算"); return;
        }
        current.put("ratio", round((value.doubleValue() - base.doubleValue()) / base.doubleValue() * 100));
        current.put("ratioNote", "");
    }

    private static Map<String, Object> finding(String code, String meterId, String title, String detail) {
        return finding(code, meterId, title, detail, "完整边界、无回退/同时间冲突且读取覆盖完整");
    }

    private static Map<String, Object> finding(String code, String meterId, String title, String detail, String rule) {
        return Map.of("code", code, "meterId", meterId, "title", title, "detail", detail, "period", 0, "rule", rule);
    }

    private static Map<String, Object> check(String code, String label, List<Map<String, Object>> rows) {
        List<String> ids = rows.stream().filter(row -> "ready".equals(row.get("status")))
                .map(row -> String.valueOf(((Map<?, ?>) row.get("meter")).get("id"))).toList();
        return Map.of("code", code, "label", label, "status", ids.size() == rows.size() ? "complete" : ids.isEmpty() ? "not_checked" : "partial",
                "checkedMeterIds", ids);
    }
    private Map<String, Object> energyBusinessPayload(
            ObservationRequest request, List<AuthorizedDevice> meters, List<MeterRead> reads,
            List<ReportWindow> windows, String kind, Map<String, String> parameters) {
        Instant selectedFrom = windows.get(windows.size() - 1).from();
        Instant selectedTo = windows.get(windows.size() - 1).to();
        boolean policyTenant = policy != null && request.identity().tenantCode().equals(policy.tenantCode())
                && policy.version() != null;
        boolean policyComplete = policyTenant && meters.stream().allMatch(m -> {
            CompanyEnergyPolicy.Meter configured = policy.meters().get(m.id());
            return configured != null && configured.unit() != null && configured.multiplier() != null;
        });
        List<Map<String, Object>> meterRows = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            MeterRead read = reads.stream().filter(r -> r.device.id().equals(meter.id())).findFirst()
                    .orElse(new MeterRead(meter, List.of(), true));
            CompanyEnergyPolicy.Meter configured = policyTenant ? policy.meters().get(meter.id()) : null;
            List<Map<String, Object>> periods = new ArrayList<>();
            for (ReportWindow window : windows) {
                Map<String, Object> period = new LinkedHashMap<>(
                        comparisonPeriod(read.samples, window.from(), window.to(), read.truncated, format(window.from()).substring(0, 10)));
                applyPolicy(period, configured);
                periods.add(period);
            }
            if (periods.size() > 1) annotateRatio(periods.get(periods.size() - 2), periods.get(periods.size() - 1),
                    windows.get(windows.size() - 2), windows.get(windows.size() - 1));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meter", Map.of("id", meter.id(), "name", meter.name(), "location", meter.installAddress()));
            row.put("status", read.samples.isEmpty() && read.truncated ? "failed" : "ready");
            row.put("error", read.truncated ? "读取页数预算用尽，保留已取得采样" : "");
            row.put("unit", configured == null ? null : configured.unit());
            row.put("multiplier", configured == null ? null : configured.multiplier());
            row.put("periods", periods);
            row.put("series", reportSeries(read, configured, selectedFrom, selectedTo));
            row.put("samples", read.samples.size());
            row.put("complete", !read.truncated);
            row.put("latest", formatNullable(latestSample(read.samples)));
            meterRows.add(row);
        }
        List<Map<String, Object>> periodRows = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            ReportWindow window = windows.get(index);
            List<String> validIds = new ArrayList<>();
            double sum = 0;
            for (Map<String, Object> meter : meterRows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> period = (Map<String, Object>) ((List<?>) meter.get("periods")).get(index);
                if (policyComplete && "comparable".equals(period.get("status")) && period.get("usage") instanceof Number value) {
                    validIds.add(String.valueOf(((Map<?, ?>) meter.get("meter")).get("id")));
                    sum += value.doubleValue();
                }
            }
            Map<String, Object> metric = new LinkedHashMap<>();
            if (policyComplete && !validIds.isEmpty()) {
                metric.put("value", round(sum)); metric.put("unit", "kWh");
                metric.put("status", validIds.size() == meters.size() ? "complete" : "partial");
                metric.put("meterIds", validIds);
            } else {
                metric = null;
            }
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("from", format(window.from())); period.put("to", format(window.to()));
            if (window.label() != null) period.put("label", window.label());
            period.put("metric", metric);
            periodRows.add(period);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> total = periodRows.isEmpty() ? null : (Map<String, Object>) periodRows.get(periodRows.size() - 1).get("metric");
        summary.put("total", total); summary.put("average",
                total == null || !"complete".equals(total.get("status"))
                        ? null : round(((Number) total.get("value")).doubleValue() / dayCount(selectedFrom, selectedTo)));
        if ("comparison".equals(kind) && periodRows.size() > 1) {
            Map<String, Object> previous = (Map<String, Object>) periodRows.get(periodRows.size() - 2).get("metric");
            summary.put("previous", previous);
            Double difference = difference(total, previous);
            summary.put("difference", difference);
            summary.put("ratio", ratio(total, previous, windows.get(windows.size() - 2), windows.get(windows.size() - 1)));
            summary.put("ratioNote", ratioNote(total, previous, windows.get(windows.size() - 2), windows.get(windows.size() - 1)));
        }
        Map<String, Object> coverage = new LinkedHashMap<>();
        int complete = (int) reads.stream().filter(read -> !read.truncated).count();
        int valid = (int) meterRows.stream().filter(row -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> periods = (List<Map<String, Object>>) row.get("periods");
            return !periods.isEmpty() && "comparable".equals(periods.get(periods.size() - 1).get("status"));
        }).count();
        coverage.put("requestedMeters", meters.size()); coverage.put("completedMeters", complete);
        coverage.put("validMeters", valid); coverage.put("failedMeters", meters.size() - complete);
        coverage.put("truncated", reads.stream().anyMatch(read -> read.truncated));
        coverage.put("status", complete == meters.size() && valid == meters.size() ? "ready" : "partial");
        coverage.put("remainingMeterIds", meterRows.stream().filter(row -> !"ready".equals(row.get("status")))
                .map(row -> String.valueOf(((Map<?, ?>) row.get("meter")).get("id"))).toList());
        coverage.put("reason", complete == meters.size() ? "" : "部分电表未读完，详见逐表原因");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", java.util.UUID.randomUUID().toString());
        result.put("type", "energy-report"); result.put("source", "company"); result.put("kind", kind);
        result.put("observedAt", clock.instant().toString());
        result.put("requested", Map.of("from", format(selectedFrom), "to", format(selectedTo)));
        result.put("windows", windows.stream().map(w -> {
            Map<String, Object> out = new LinkedHashMap<>(); out.put("from", format(w.from())); out.put("to", format(w.to()));
            if (w.label() != null) out.put("label", w.label()); return out;
        }).toList());
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf"); basis.put("timezone", "Asia/Shanghai");
        basis.put("policyVersion", policyComplete ? policy.version() : null);
        basis.put("aggregation", policyComplete ? "verified" : "pending"); basis.put("upstream", "ENERGY_HISTORY");
        result.put("basis", basis);
        result.put("meters", meterRows); result.put("periods", periodRows);
        result.put("series", reportSeries(meterRows, selectedFrom, selectedTo));
        result.put("seriesCoverage", seriesCoverage(selectedFrom, selectedTo));
        result.put("summary", summary); result.put("coverage", coverage);
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> notChecked = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        if ("inspection".equals(kind)) {
            for (Map<String, Object> row : meterRows) {
                String id = String.valueOf(((Map<?, ?>) row.get("meter")).get("id"));
                @SuppressWarnings("unchecked") List<Map<String, Object>> periods = (List<Map<String, Object>>) row.get("periods");
                if (!"ready".equals(row.get("status"))) findings.add(finding("read_failed", id, "采样读取未完成", String.valueOf(row.get("error"))));
                if (row.get("unit") == null) findings.add(finding("basis", id, "计量口径未确认", "单位/倍率未确认，保留原始示数差，不换算或跨表相加"));
                for (Map<String, Object> period : periods) if (!"comparable".equals(period.get("status"))) {
                    findings.add(finding("coverage", id, "采样需要核查", String.valueOf(period.get("notes")), "完整边界、无回退/同时间冲突且读取覆盖完整"));
                }
                if (parameters.containsKey("growthThreshold")) {
                    Map<String, Object> latest = periods.get(periods.size() - 1);
                    Object growth = latest.get("ratio");
                    if (growth instanceof Number value
                            && value.doubleValue() > Double.parseDouble(parameters.get("growthThreshold"))) {
                        findings.add(finding("growth", id, "区间增幅超过检查阈值",
                                "增幅 " + value + "% · 阈值 " + parameters.get("growthThreshold") + "%"));
                    } else if (!(growth instanceof Number)) {
                        notChecked.add(Map.of("code", "growth", "meterId", id,
                                "reason", String.valueOf(latest.get("ratioNote"))));
                    }
                }
            }
            checks.add(check("coverage", "请求边界与读取覆盖", meterRows));
            checks.add(check("counter", "累计回退与同时间冲突", meterRows));
            checks.add(check("basis", "计量口径", meterRows));
        } else {
            checks = List.of();
        }
        result.put("checks", checks); result.put("notChecked", notChecked); result.put("findings", findings);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("growthThreshold", parameters.containsKey("growthThreshold")
                ? Double.valueOf(parameters.get("growthThreshold")) : null);
        result.put("rules", rules);
        return result;
    }
    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            requireCompany(request.identity());
            if (request.operation() != ObservationOperationEnum.COMPANY_ENERGY_DAILY
                    && request.operation() != ObservationOperationEnum.ENERGY_COMPARISON
                    && request.operation() != ObservationOperationEnum.COMPANY_ENERGY_REPORT
                    && request.operation() != ObservationOperationEnum.COMPANY_METER_REPORT) {
                return Mono.error(new ObservationUnavailableException("公司能耗组合器不支持该观察操作"));
            }
            return authorizedScope(request.identity())
                    .switchIfEmpty(Mono.error(new ObservationUnavailableException("公司电表授权范围为空")))
                    .flatMap(scope -> {
                        validateScope(request.identity(), scope);
                        List<AuthorizedDevice> meters = energyMeters(scope);
                        if (meters.isEmpty()) return Mono.error(new BusinessException("FORBIDDEN", "当前来源没有公司电表观察范围", 403));
                        if (request.operation() == ObservationOperationEnum.COMPANY_ENERGY_DAILY) {
                            return daily(request, meters);
                        }
                        if (request.operation() == ObservationOperationEnum.ENERGY_COMPARISON) {
                            return comparison(request, meters);
                        }
                        if (request.operation() == ObservationOperationEnum.COMPANY_ENERGY_REPORT) {
                            return monthlyReport(request, meters);
                        }
                        return meterReport(request, meters);
                    })
                    .doOnError(error -> {
                        if (error instanceof BusinessException business
                                && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
                            history.discard(historyKey(request.identity()));
                        }
                    })
                    .onErrorMap(error -> error instanceof BusinessException
                            ? error : new ObservationUnavailableException("公司电量读取失败"));
        });
    }

    private Mono<ObservationResponse> daily(ObservationRequest request, List<AuthorizedDevice> meters) {
        int days = integer(request.parameters().get("days"), 30, 1, MAX_DAYS);
        String requestedEnd = request.parameters().get("end");
        LocalDate end = requestedEnd == null ? LocalDate.now(clock.withZone(SHANGHAI)).minusDays(1) : date(requestedEnd);
        LocalDate start = end.minusDays(days - 1L);
        Instant from = samplingBoundary(start).minusSeconds(TWELVE_HOURS_SECONDS);
        Instant to = samplingBoundary(end.plusDays(1));
        Instant now = clock.instant();
        Instant readTo = to.isAfter(now) ? to : now;
        // Strict read plus the wall-clock budget: work stops early as truncation instead of
        // letting one slow catalog hit the upstream timeout and turn the strip into a 503.
        Mono<List<MeterRead>> reads = historySnapshots == null
                ? readCompanyStream(request.identity(), meters, from, to, readTo)
                : Mono.fromCallable(() -> readDailyHistory(request.identity(), meters, from, to, readTo))
                        .subscribeOn(Schedulers.boundedElastic());
        return reads.map(result -> energyResponse(dailyPayload(meters, result, start, end, days)));
    }

    /**
     * Composes daily history from persisted natural-month blocks. The blocks are
     * stable date ranges, so overlapping and fully contained windows reuse them
     * without walking upstream pages again. Month blocks also carry the boundary
     * anchor before the first day.
     */
    private List<MeterRead> readDailyHistory(
            RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant to, Instant readTo) {
        List<EnergyHistoryStore.Reading> combined = new ArrayList<>();
        YearMonth first = YearMonth.from(from.plusSeconds(TWELVE_HOURS_SECONDS).atZone(SHANGHAI));
        YearMonth last = YearMonth.from(to.atZone(SHANGHAI).toLocalDate().minusDays(1));
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            Instant monthStart = month.atDay(1).atStartOfDay(SHANGHAI).toInstant();
            if (monthStart.isAfter(readTo)) break;
            combined.addAll(blockReadings(loadMonthBlock(identity, meters, month)));
        }
        YearMonth currentMonth = YearMonth.now(clock.withZone(SHANGHAI));
        if (currentMonth.isAfter(last)) {
            Instant currentStart = currentMonth.atDay(1).atStartOfDay(SHANGHAI).toInstant();
            if (!currentStart.isAfter(readTo)) {
                // The previous implementation read through "now" even for a historical
                // window, so latestSampleTime and per-meter latest included current data.
                // Restore that metadata/comparison basis from the fixed CURRENT month block.
                combined.addAll(blockReadings(loadMonthBlock(identity, meters, currentMonth)));
            }
        }
        return buildMeterReads(meters, mergeReadings(
                List.<EnergyHistoryStore.Reading>of(), combined), from, false);
    }

    /**
     * Raw samples for metered statistics, keyed by the same natural-month blocks
     * the daily strip uses. Blocks are scoped to the full authorized meter set,
     * so overlapping statistics, comparison, night and region windows reuse the
     * held samples instead of walking per-meter upstream pages again.
     */
    private List<MeterRead> readMonthBlocks(
            RequestIdentity identity, List<AuthorizedDevice> authorized, List<AuthorizedDevice> needed,
            Instant from, Instant to) {
        List<EnergyHistoryStore.Reading> combined = new ArrayList<>();
        YearMonth first = YearMonth.from(from.atZone(SHANGHAI));
        YearMonth last = YearMonth.from(to.atZone(SHANGHAI));
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            for (EnergyHistoryStore.Reading reading : blockReadings(loadMonthBlock(identity, authorized, month))) {
                if (!reading.readTime().isAfter(to)) combined.add(reading);
            }
        }
        return buildMeterReads(needed, mergeReadings(List.<EnergyHistoryStore.Reading>of(), combined), from, false);
    }

    private Map<String, Object> loadMonthBlock(
            RequestIdentity identity, List<AuthorizedDevice> meters, YearMonth month) {
        List<String> meterIds = meters.stream().map(AuthorizedDevice::id).sorted().toList();
        String scope = digest(identity.source() + "\u0000" + String.join(",", meterIds));
        ReadSnapshots.Identity snapshotIdentity = new ReadSnapshots.Identity(
                identity.tenantCode(), identity.authorization(), identity.source().name(), identity.ownerKey());
        Instant monthEnd = month.plusMonths(1).atDay(1).atTime(8, 0).atZone(SHANGHAI).toInstant();
        ReadSnapshots.PolicyEnum policy = monthEnd.isAfter(clock.instant())
                ? ReadSnapshots.PolicyEnum.CURRENT : ReadSnapshots.PolicyEnum.HISTORICAL;
        // Earlier bodies used unreliable upstream time filters and may contain false empty months.
        return historySnapshots.load(snapshotIdentity, "ENERGY_HISTORY_MONTH:v4:" + scope,
                Map.of("month", month.toString()), policy,
                () -> fetchMonthBlock(identity, meters, month));
    }

    private Map<String, Object> fetchMonthBlock(
            RequestIdentity identity, List<AuthorizedDevice> meters, YearMonth month) {
        Instant monthStart = month.atDay(1).atStartOfDay(SHANGHAI).toInstant();
        Instant monthEnd = month.plusMonths(1).atDay(1).atTime(8, 0).atZone(SHANGHAI).toInstant();
        // Recompute the cutoff on every loader execution; a scheduled refresh must
        // not keep publishing yesterday's read horizon as fresh.
        Instant now = clock.instant();
        Instant blockTo = monthEnd.isAfter(now) ? now : monthEnd;
        if (!monthStart.isBefore(blockTo)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("month", month.toString());
            empty.put("from", monthStart.toString());
            empty.put("to", monthStart.toString());
            empty.put("readings", List.of());
            return empty;
        }
        // Runtime's upstream contract: neither startTime nor endTime selects readTime reliably.
        // Locate this month in the unfiltered ordered stream, then filter timestamps locally.
        List<EnergyHistoryStore.Reading> raw = mergeReadings(
                List.<EnergyHistoryStore.Reading>of(), readMonthStream(identity, monthStart, blockTo));
        List<EnergyHistoryStore.Reading> fetched = raw.stream().filter(reading ->
                !reading.readTime().isBefore(monthStart) && !reading.readTime().isAfter(blockTo)).toList();
        Map<String, EnergyHistoryStore.Reading> boundary = new HashMap<>();
        for (EnergyHistoryStore.Reading reading : raw) {
            if (!reading.readTime().isBefore(monthStart)) continue;
            EnergyHistoryStore.Reading prior = boundary.get(reading.meterId());
            if (prior == null || reading.readTime().isAfter(prior.readTime())) {
                boundary.put(reading.meterId(), reading);
            }
        }
        Set<String> authorized = Set.copyOf(meters.stream().map(AuthorizedDevice::id).toList());
        Set<String> needingAnchor = new LinkedHashSet<>();
        for (EnergyHistoryStore.Reading reading : fetched) {
            if (authorized.contains(reading.meterId()) && !boundary.containsKey(reading.meterId())) {
                needingAnchor.add(reading.meterId());
            }
        }
        if (!needingAnchor.isEmpty()) {
            long anchorDeadline = deadline();
            Map<String, EnergyHistoryStore.Reading> anchors = Flux.fromIterable(needingAnchor)
                    .flatMap(meterId -> readBoundaryAnchor(identity, meterId, monthStart, anchorDeadline),
                            METER_CONCURRENCY)
                    .collectMap(EnergyHistoryStore.Reading::meterId, reading -> reading)
                    .block();
            if (anchors != null) boundary.putAll(anchors);
        }
        Map<String, EnergyHistoryStore.Reading> kept = new LinkedHashMap<>();
        for (EnergyHistoryStore.Reading reading : fetched) kept.put(readingKey(reading), reading);
        for (EnergyHistoryStore.Reading reading : boundary.values()) kept.put(readingKey(reading), reading);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EnergyHistoryStore.Reading reading : kept.values()) {
            if (!authorized.contains(reading.meterId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meterId", reading.meterId());
            row.put("id", reading.id());
            row.put("readTime", reading.readTime().toString());
            row.put("value", reading.value());
            row.put("powerFactor", reading.powerFactor());
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("month", month.toString());
        body.put("from", monthStart.toString());
        body.put("to", blockTo.toString());
        body.put("readings", rows);
        return body;
    }

    private List<EnergyHistoryStore.Reading> readMonthStream(
            RequestIdentity identity, Instant monthStart, Instant monthEnd) {
        long deadlineAt = deadline();
        Map<Integer, StreamPage> pages = new java.util.TreeMap<>();
        StreamPage first = monthPage(identity, 0, pages, deadlineAt);
        if (first.samples().isEmpty()) return List.of();
        int totalPages = first.totalElements() < 0 ? -1
                : (int) Math.min(Integer.MAX_VALUE, (first.totalElements() + 499L) / 500L);
        int startPage = 0;
        if (totalPages > 1 && oldestSample(first).isAfter(monthEnd)) {
            int left = 1, right = totalPages;
            while (left < right) {
                int distance = right - left;
                int probes = Math.min(4, distance);
                List<Integer> indices = new ArrayList<>(probes);
                for (int index = 0; index < probes; index++) {
                    indices.add(distance <= 4 ? left + index
                            : left + (index + 1) * distance / (probes + 1));
                }
                monthPages(identity, indices, pages, deadlineAt);
                int previous = left - 1;
                boolean found = false;
                for (int index : indices) {
                    StreamPage probe = pages.get(index);
                    if (probe.samples().isEmpty() || !oldestSample(probe).isAfter(monthEnd)) {
                        left = previous + 1;
                        right = index;
                        found = true;
                        break;
                    }
                    previous = index;
                }
                if (!found) left = previous + 1;
            }
            startPage = left;
            if (startPage == totalPages) return List.of();
        }
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        StreamPage seed = monthPage(identity, startPage, pages, deadlineAt);
        chunks.add(seed.samples());
        if (seed.last() || seed.samples().isEmpty()) return readings(chunks);
        // A timestamp group can straddle two pages. Include the following page when
        // the first page already crosses the month boundary, rather than re-reading
        // that missing boundary separately for every meter.
        boolean crossed = oldestSample(seed).isBefore(monthStart);
        for (int page = startPage + 1; ; ) {
            int count = crossed ? 1 : STREAM_PAGE_CONCURRENCY;
            if (totalPages >= 0) count = Math.min(count, totalPages - page);
            if (count <= 0) return readings(chunks);
            count = Math.min(count, Math.max(1, maxPages - pages.size()));
            List<Integer> indices = new ArrayList<>(count);
            for (int index = 0; index < count; index++) indices.add(page + index);
            monthPages(identity, indices, pages, deadlineAt);
            boolean ended = crossed;
            for (int index : indices) {
                StreamPage current = pages.get(index);
                chunks.add(current.samples());
                ended |= current.last() || current.samples().isEmpty()
                        || oldestSample(current).isBefore(monthStart);
            }
            if (ended) return readings(chunks);
            page += count;
        }
    }

    private StreamPage monthPage(RequestIdentity identity, int page,
            Map<Integer, StreamPage> pages, long deadlineAt) {
        monthPages(identity, List.of(page), pages, deadlineAt);
        return pages.get(page);
    }

    private void monthPages(RequestIdentity identity, List<Integer> indices,
            Map<Integer, StreamPage> pages, long deadlineAt) {
        List<Integer> missing = indices.stream().filter(index -> !pages.containsKey(index)).toList();
        if (missing.isEmpty()) return;
        if (pages.size() + missing.size() > maxPages || System.currentTimeMillis() >= deadlineAt) {
            throw new ObservationUnavailableException("计量历史月块读取达到预算");
        }
        List<Map.Entry<Integer, StreamPage>> loaded = Flux.fromIterable(missing)
                .flatMapSequential(index -> fetchStreamPage(identity, null, null, index)
                        .map(page -> Map.entry(index, page)), STREAM_PAGE_CONCURRENCY)
                .collectList().block();
        if (loaded == null) throw new ObservationUnavailableException("计量历史月块读取未完成");
        for (Map.Entry<Integer, StreamPage> entry : loaded) {
            if (entry.getValue().partial()) {
                throw new ObservationUnavailableException("计量历史月块读取未完成");
            }
            pages.put(entry.getKey(), entry.getValue());
        }
        StreamPage previous = null;
        for (StreamPage current : pages.values()) {
            if (previous != null && !previous.samples().isEmpty() && !current.samples().isEmpty()
                    && oldestSample(previous).isBefore(newestSample(current))) {
                throw new ObservationUnavailableException("计量历史跨页时间顺序变化");
            }
            previous = current;
        }
    }

    private static Instant oldestSample(StreamPage page) {
        Instant result = Instant.MAX;
        for (Map<String, Object> sample : page.samples()) {
            Instant at = instantOrNull(text(sample.get("readTime")));
            if (at == null) throw new ObservationUnavailableException("计量样本时间格式无效");
            if (at.isBefore(result)) result = at;
        }
        return result;
    }

    private static Instant newestSample(StreamPage page) {
        Instant result = Instant.MIN;
        for (Map<String, Object> sample : page.samples()) {
            Instant at = instantOrNull(text(sample.get("readTime")));
            if (at == null) throw new ObservationUnavailableException("计量样本时间格式无效");
            if (at.isAfter(result)) result = at;
        }
        return result;
    }

    /** Reads a sparse left anchor without the gateway's unreliable date filters. */
    private Mono<EnergyHistoryStore.Reading> readBoundaryAnchor(
            RequestIdentity identity, String meterId, Instant monthStart, long deadlineAt) {
        return readBoundaryPage(identity, meterId, monthStart, deadlineAt, 0);
    }

    private Mono<EnergyHistoryStore.Reading> readBoundaryPage(
            RequestIdentity identity, String meterId, Instant monthStart, long deadlineAt, int page) {
        if (page >= maxPages || System.currentTimeMillis() >= deadlineAt) {
            return Mono.error(new ObservationUnavailableException("计量历史边界锚点读取达到预算"));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("equipmentId", meterId);
        params.put("page", Integer.toString(page));
        params.put("size", "500");
        return rawHistory.read(new ObservationRequest(identity, ObservationOperationEnum.ENERGY_HISTORY, params))
                .flatMap(response -> {
                    if (response.status() == ObservationStatusEnum.EMPTY) return Mono.empty();
                    if (response.status() != ObservationStatusEnum.AVAILABLE
                            && response.status() != ObservationStatusEnum.PARTIAL) {
                        return Mono.error(new ObservationUnavailableException("计量历史边界锚点上游不可用"));
                    }
                    boolean continuationOnly = response.payload().get("nextPage") != null
                            && response.failures().stream().allMatch(failure -> "HISTORY_PARTIAL".equals(failure.code()));
                    if (response.status() == ObservationStatusEnum.PARTIAL && !continuationOnly) {
                        return Mono.error(new ObservationUnavailableException("计量历史边界锚点读取不完整"));
                    }
                    List<Sample> pageRows = parseRows(response.payload(), meterId);
                    Sample candidate = pageRows.stream()
                            .filter(sample -> !sample.readTime.isAfter(monthStart))
                            .max(Comparator.comparing(Sample::readTime)).orElse(null);
                    if (candidate != null) {
                        return Mono.just(new EnergyHistoryStore.Reading(
                                meterId, candidate.id, candidate.readTime, candidate.value, candidate.powerFactor));
                    }
                    if (isLast(response.payload())) return Mono.empty();
                    return readBoundaryPage(identity, meterId, monthStart, deadlineAt, page + 1);
                })
                .onErrorMap(error -> error instanceof BusinessException
                        ? error : new ObservationUnavailableException("计量历史边界锚点读取失败"));
    }

    private static List<EnergyHistoryStore.Reading> blockReadings(Map<String, Object> body) {
        Object raw = body.get("readings");
        if (!(raw instanceof List<?> rows)) {
            throw new ObservationUnavailableException("计量历史月块格式无效");
        }
        List<EnergyHistoryStore.Reading> result = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) throw new ObservationUnavailableException("计量历史月块格式无效");
            String meterId = text(row.get("meterId"));
            String readTime = text(row.get("readTime"));
            Double epf = numberOrNull(row.get("value"));
            Instant at = instantOrNull(readTime);
            if (meterId == null || at == null || epf == null) {
                throw new ObservationUnavailableException("计量历史月块样本无效");
            }
            result.add(new EnergyHistoryStore.Reading(meterId, text(row.get("id")), at, epf, numberOrNull(row.get("powerFactor"))));
        }
        return result;
    }

    private Mono<ObservationResponse> comparison(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        String id = required(p, "equipmentId");
        AuthorizedDevice meter = meters.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (meter == null) throw new BusinessException("FORBIDDEN", "该计量点不在当前公司观察范围内", 403);
        int count = integer(p.get("weeks"), 4, 2, 6);
        int boundary = boundaryHour(p.get("boundaryHour"));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate requestedEnd = p.containsKey("endDate") ? date(p.get("endDate")) : today;
        LocalDate endDate = requestedEnd.minusDays((requestedEnd.getDayOfWeek().getValue() + 6) % 7L);
        Instant end = local(endDate).toInstant().plusSeconds(boundary * 3600L);
        if (p.containsKey("endDate")) {
            if (!requestedEnd.equals(endDate) || end.isAfter(now)) {
                throw new ObservationUnavailableException("结束日须为已到达的周一");
            }
        } else if (end.isAfter(now)) {
            end = end.minus(7, ChronoUnit.DAYS);
        }
        Instant start = end.minus((count + 1L) * 7L, ChronoUnit.DAYS);
        Instant finalEnd = end;
        return readMeterHistory(request.identity(), meters, List.of(meter), start,
                now.isBefore(end.plus(7, ChronoUnit.DAYS)) ? now : end.plus(7, ChronoUnit.DAYS), false)
                .map(reads -> energyResponse(comparisonPayload(meter, reads.get(0), start, finalEnd, count, boundary, now)));
    }

    private Mono<ObservationResponse> monthlyReport(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        int months = integer(p.get("months"), 3, 1, 12);
        int boundary = boundaryHour(p.get("boundaryHour"));
        YearMonth latest = YearMonth.now(clock.withZone(SHANGHAI));
        YearMonth endMonth = p.containsKey("endMonth") ? yearMonth(p.get("endMonth")) : latest;
        if (endMonth.isAfter(latest)) throw new ObservationUnavailableException("结束月份须为已到达月份");
        List<MonthWindow> windows = new ArrayList<>();
        for (int index = 0; index <= months; index++) {
            YearMonth month = endMonth.minusMonths(months - index);
            Instant from = month.atDay(1).atTime(boundary, 0).atZone(SHANGHAI).toInstant();
            Instant next = month.plusMonths(1).atDay(1).atTime(boundary, 0).atZone(SHANGHAI).toInstant();
            windows.add(new MonthWindow(month, from, next.isAfter(clock.instant()) ? clock.instant() : next,
                    next.isAfter(clock.instant())));
        }
        if (windows.get(windows.size() - 1).from.isAfter(clock.instant())) {
            throw new ObservationUnavailableException("本月采样边界尚未到达");
        }
        Instant from = windows.get(0).from.minusSeconds(TWELVE_HOURS_SECONDS);
        Instant to = windows.get(windows.size() - 1).to;
        return readMeterHistory(request.identity(), meters, meters, from, to, true)
                .map(reads -> energyResponse(monthlyPayload(meters, reads, windows, boundary)));
    }

    private static int boundaryHour(String value) {
        if (value == null) return 8;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed == 0 || parsed == 8) return parsed;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new ObservationUnavailableException("边界时刻仅支持零点或08:00");
    }

    private static YearMonth yearMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException error) {
            throw new ObservationUnavailableException("结束月份格式无效");
        }
    }

    private Mono<ObservationResponse> meterReport(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        Instant from = instant(required(p, "from"));
        Instant to = instant(required(p, "to"));
        if (!from.isBefore(to) || to.isAfter(clock.instant())) {
            return Mono.error(new ObservationUnavailableException("计量报告时间窗口无效"));
        }
        List<AuthorizedDevice> selected = selectMeters(meters, p);
        if (selected.isEmpty()) {
            if (!"all".equals(p.getOrDefault("scopeLevel", "all"))) {
                throw new BusinessException("FORBIDDEN", "所选计量范围不在当前授权范围内", 403);
            }
            throw new ObservationUnavailableException("计量范围内没有授权电表");
        }
        boolean comparison = "comparison".equalsIgnoreCase(p.getOrDefault("kind", "statistics"));
        if (!Set.of("statistics", "comparison").contains(p.getOrDefault("kind", "statistics"))
                || p.containsKey("grain") && !Set.of("hour", "day", "week", "month").contains(p.get("grain"))
                || comparison && !Set.of("time", "region").contains(p.getOrDefault("axis", "time"))) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "计量报告种类、粒度或对比轴无效", 400);
        }
        if ((p.containsKey("windowFrom") || p.containsKey("windowTo"))
                && (comparison || !"20:00".equals(p.get("windowFrom")) || !"08:00".equals(p.get("windowTo")))) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "每日时段统计目前仅支持20:00至次日08:00，且不接受对比参数", 400);
        }
        if (!comparison && (p.containsKey("baseFrom") || p.containsKey("baseTo"))) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "统计报告不接受基期或对照范围", 400);
        }
        if (comparison && "region".equalsIgnoreCase(p.getOrDefault("axis", "time"))) {
            if (p.containsKey("baseFrom") || p.containsKey("baseTo")) {
                throw new BusinessException("INVALID_ENERGY_WINDOW", "区域对比固定时间跨度，不接受基期", 400);
            }
            if (!p.containsKey("otherScopeLevel")) {
                throw new BusinessException("INVALID_ENERGY_WINDOW", "缺少对照计量范围", 400);
            }
            List<AuthorizedDevice> other = selectMeters(meters, otherScopeParams(p));
            if (other.isEmpty()) {
                throw new BusinessException("FORBIDDEN", "对照计量范围不在当前授权范围内", 403);
            }
            return readMeterHistory(request.identity(), meters, meters, from.minus(7, ChronoUnit.DAYS), to, false)
                    .map(reads -> energyResponse(MeterReports.region(
                            baseReport(request, selected, reads, from, to, "comparison"),
                            scopeParam(request, selected), scopeParam(otherScopeParams(p), other),
                            selected, other, reads, from.toEpochMilli(), to.toEpochMilli())));
        }
        boolean hasOther = p.containsKey("otherScopeLevel") || p.containsKey("otherFloor") || p.containsKey("otherText")
                || p.containsKey("otherMeterId") || p.containsKey("otherMeterIds");
        if (hasOther) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", comparison
                    ? "时间对比固定计量范围，不接受另一个范围" : "统计报告不接受基期或对照范围", 400);
        }
        boolean nightly = p.containsKey("windowFrom") || p.containsKey("windowTo");
        if (nightly) {
            return readMeterHistory(request.identity(), meters, selected, from.minus(7, ChronoUnit.DAYS), to, false)
                    .map(reads -> energyResponse(NightMeterReport.build(
                            baseReport(request, selected, reads, from, to, "statistics"), request, selected, reads,
                            from.toEpochMilli(), to.toEpochMilli())));
        }
        Instant baseFrom = null, baseTo = null;
        if (comparison) {
            baseFrom = p.containsKey("baseFrom") ? instant(p.get("baseFrom")) : from.minus(to.toEpochMilli() - from.toEpochMilli(), ChronoUnit.MILLIS);
            baseTo = p.containsKey("baseTo") ? instant(p.get("baseTo")) : from;
            if (!baseFrom.isBefore(baseTo) || baseTo.isAfter(clock.instant())) {
                return Mono.error(new ObservationUnavailableException("计量对比时间窗口无效"));
            }
        }
        Instant readFrom = (comparison && baseFrom.isBefore(from) ? baseFrom : from).minus(7, ChronoUnit.DAYS);
        Instant readTo = comparison ? (baseTo.isAfter(to) ? baseTo : to) : to;
        Instant finalBaseFrom = baseFrom, finalBaseTo = baseTo;
        return readMeterHistory(request.identity(), meters, meters, readFrom, readTo, false)
                .map(reads -> energyResponse(comparison
                        ? MeterReports.time(baseReport(request, selected, reads, from, to, "comparison"),
                                scopeParam(request, selected), selected, reads, from.toEpochMilli(), to.toEpochMilli(),
                                finalBaseFrom.toEpochMilli(), finalBaseTo.toEpochMilli())
                        : MeterReports.statistics(baseReport(request, selected, reads, from, to, "statistics"),
                                p, scopeParam(request, selected), selected, reads, from.toEpochMilli(), to.toEpochMilli())));
    }
    private static ObservationResponse energyResponse(Map<String, Object> payload) {
        boolean truncated = Boolean.TRUE.equals(payload.get("truncated"));
        Object coverage = payload.get("coverage");
        if (coverage instanceof Map<?, ?> values) truncated = truncated || Boolean.TRUE.equals(values.get("truncated"));
        if (!truncated) return ObservationResponse.available(payload);
        return ObservationResponse.partial(payload,
                List.of(new ltd.qianc.iotagent.iot.ObservationFailure(
                        "energy", "HISTORY_TRUNCATED", "计量历史读取达到页/行预算，统计仅保留已取得事实")),
                false);
    }

    /**
     * Every page contains all meters. Fetch bounded batches until an actual timestamp crosses
     * the requested start (or the feed ends); sample density is never a correctness assumption.
     */
    private Mono<List<MeterRead>> readCompanyStream(
            RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant to, Instant readTo) {
        List<String> meterIds = meters.stream().map(AuthorizedDevice::id).toList();
        String key = historyKey(identity);
        EnergyHistoryStore.View view = history.view(key, meterIds, from, to, to);
        if (view.covered()) {
            return Mono.just(buildMeterReads(meters, view.readings(), from, false));
        }
        EnergyHistoryStore.Fetch fetch = history.missing(key, meterIds, from, to, readTo, to);
        long deadlineAt = deadline();
        boolean filtered = fetch.from().isAfter(from.minusSeconds(TWELVE_HOURS_SECONDS))
                || fetch.to().isBefore(readTo);
        if (!filtered) {
            return readCompanyStreamFull(identity, meters, from, readTo, key, meterIds, view, deadlineAt);
        }
        return readStreamBatch(identity, fetch.from(), fetch.from(), fetch.to(), deadlineAt, 0,
                new ArrayList<>(), false)
                .flatMap(scan -> {
                    List<EnergyHistoryStore.Reading> fetched = readings(scan.chunks());
                    boolean trustworthy = !fetched.isEmpty() && fetched.stream().allMatch(reading ->
                            !reading.readTime().isBefore(fetch.from()) && !reading.readTime().isAfter(fetch.to()));
                    if (!scan.truncated() && !trustworthy) {
                        // A date-filtered range that came back empty (or outside the range) is not
                        // proof that history is absent; fall back to the unfiltered stream.
                        return readCompanyStreamFull(identity, meters, from, readTo, key, meterIds, view, deadlineAt);
                    }
                    List<EnergyHistoryStore.Reading> combined = mergeReadings(view.readings(), fetched);
                    if (!scan.truncated()) {
                        history.record(key, meterIds, fetch.from(), fetch.to(), fetched);
                    }
                    return Mono.just(buildMeterReads(meters, combined, from, scan.truncated()));
                });
    }

    private Mono<List<MeterRead>> readCompanyStreamFull(
            RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant readTo,
            String key, List<String> meterIds, EnergyHistoryStore.View view, long deadlineAt) {
        return readStreamBatch(identity, from, null, null, deadlineAt, 0, new ArrayList<>(), false)
                .map(scan -> {
                    List<EnergyHistoryStore.Reading> fetched = readings(scan.chunks());
                    List<EnergyHistoryStore.Reading> combined = mergeReadings(view.readings(), fetched);
                    if (!scan.truncated()) {
                        history.record(key, meterIds, from, readTo, fetched);
                    }
                    return buildMeterReads(meters, combined, from, scan.truncated());
                });
    }

    private Mono<StreamScan> readStreamBatch(
            RequestIdentity identity, Instant scanFrom, Instant requestFrom, Instant requestTo,
            long deadlineAt, int page, List<List<Map<String, Object>>> chunks, boolean partial) {
        if (page >= maxPages || System.currentTimeMillis() >= deadlineAt) {
            return Mono.just(new StreamScan(chunks, true));
        }
        int count = Math.min(STREAM_PAGE_CONCURRENCY, maxPages - page);
        return Flux.range(page, count)
                .flatMapSequential(index -> fetchStreamPage(identity, requestFrom, requestTo, index),
                        STREAM_PAGE_CONCURRENCY)
                .collectList()
                .flatMap(batch -> {
                    boolean reachedStart = false;
                    boolean ended = false;
                    boolean incomplete = partial;
                    for (StreamPage result : batch) {
                        chunks.add(result.samples());
                        incomplete |= result.partial();
                        ended |= result.last();
                        for (Map<String, Object> sample : result.samples()) {
                            Instant time = instantOrNull(text(sample.get("readTime")));
                            if (time == null) {
                                return Mono.error(new ObservationUnavailableException("计量样本时间格式无效"));
                            }
                            reachedStart |= time.isBefore(scanFrom);
                        }
                    }
                    if (reachedStart || ended) return Mono.just(new StreamScan(chunks, incomplete));
                    return readStreamBatch(identity, scanFrom, requestFrom, requestTo, deadlineAt,
                            page + count, chunks, incomplete);
                });
    }

    private Mono<StreamPage> fetchStreamPage(
            RequestIdentity identity, Instant requestFrom, Instant requestTo, int page) {
        Map<String, String> params = new LinkedHashMap<>();
        if (requestFrom != null) params.put("from", format(requestFrom));
        if (requestTo != null) params.put("to", format(requestTo));
        params.put("page", Integer.toString(page));
        params.put("size", "500");
        return rawHistory.read(new ObservationRequest(identity, ObservationOperationEnum.ENERGY_HISTORY, params))
                .flatMap(response -> {
                    if (response.status() == ObservationStatusEnum.EMPTY) {
                        return Mono.just(new StreamPage(List.of(), true, false, 0));
                    }
                    if (response.status() != ObservationStatusEnum.AVAILABLE
                            && response.status() != ObservationStatusEnum.PARTIAL) {
                        return Mono.error(new ObservationUnavailableException("计量样本流上游不可用"));
                    }
                    List<Map<String, Object>> samples = streamSamples(response.payload());
                    boolean continuationOnly = response.payload().get("nextPage") != null
                            && response.failures().stream().allMatch(failure -> "HISTORY_PARTIAL".equals(failure.code()));
                    Object total = response.payload().get("totalElements");
                    if (total == null && response.payload().get("content") instanceof Map<?, ?> content) {
                        total = content.get("totalElements");
                    }
                    return Mono.just(new StreamPage(samples, isLast(response.payload()),
                            response.status() == ObservationStatusEnum.PARTIAL && !continuationOnly,
                            total instanceof Number count ? count.longValue() : -1));
                })
                .onErrorMap(error -> error instanceof BusinessException
                        ? error : new ObservationUnavailableException("计量样本流读取失败"));
    }

    private record StreamPage(List<Map<String, Object>> samples, boolean last, boolean partial, long totalElements) { }
    private record StreamScan(List<List<Map<String, Object>>> chunks, boolean truncated) { }

    private static String historyKey(RequestIdentity identity) {
        // ownerKey normally already hashes the credential, but the explicit digest keeps a
        // custom ownerKey from reusing another token's authorized scope.
        return identity.tenantCode() + '\u0000' + identity.source() + '\u0000' + identity.ownerKey()
                + '\u0000' + digest(identity.authorization());
    }

    private static String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<EnergyHistoryStore.Reading> readings(List<List<Map<String, Object>>> chunks) {
        List<EnergyHistoryStore.Reading> result = new ArrayList<>();
        for (List<Map<String, Object>> chunk : chunks) {
            for (Map<String, Object> sample : chunk) {
                String meterId = text(sample.get("equipmentId"));
                String readTime = text(sample.get("readTime"));
                Instant at = instantOrNull(readTime);
                // Malformed sample time is a content defect, never a silently skipped row.
                if (readTime != null && at == null) {
                    throw new ObservationUnavailableException("计量样本时间格式无效");
                }
                if (meterId == null || at == null) continue;
                Object values = sample.get("values");
                Double epf = values instanceof Map<?, ?> mapValues ? numberOrNull(mapValues.get("epf")) : null;
                if (epf == null) continue;
                result.add(new EnergyHistoryStore.Reading(meterId, text(sample.get("id")), at, epf,
                        numberOrNull(sample.get("totalPowerFactor"))));
            }
        }
        return result;
    }

    private static List<EnergyHistoryStore.Reading> mergeReadings(
            List<EnergyHistoryStore.Reading> cached, List<EnergyHistoryStore.Reading> fetched) {
        Map<String, EnergyHistoryStore.Reading> values = new LinkedHashMap<>();
        for (EnergyHistoryStore.Reading reading : cached) values.put(readingKey(reading), reading);
        for (EnergyHistoryStore.Reading reading : fetched) values.put(readingKey(reading), reading);
        return values.values().stream()
                .sorted(Comparator.comparing(EnergyHistoryStore.Reading::readTime)
                        .thenComparing(EnergyHistoryStore.Reading::meterId)
                        .thenComparing(EnergyHistoryStore.Reading::id))
                .toList();
    }

    private static String readingKey(EnergyHistoryStore.Reading reading) {
        return reading.meterId() + '\u0000' + reading.id() + '\u0000' + reading.readTime();
    }

    private List<MeterRead> buildMeterReads(
            List<AuthorizedDevice> meters, List<EnergyHistoryStore.Reading> readings,
            Instant from, boolean truncated) {
        Map<String, List<Sample>> inWindow = new LinkedHashMap<>();
        Map<String, Sample> anchors = new LinkedHashMap<>();
        for (EnergyHistoryStore.Reading reading : readings) {
            Sample value = new Sample(reading.id(), reading.readTime(), reading.value(), reading.powerFactor());
            if (reading.readTime().isBefore(from)) {
                // Interval differences need the last reading at or before the window start.
                Sample prior = anchors.get(reading.meterId());
                if (prior == null || reading.readTime().isAfter(prior.readTime)) {
                    anchors.put(reading.meterId(), value);
                }
            } else {
                inWindow.computeIfAbsent(reading.meterId(), key -> new ArrayList<>()).add(value);
            }
        }
        List<MeterRead> result = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            List<Sample> samples = new ArrayList<>(inWindow.getOrDefault(meter.id(), List.of()));
            Sample anchor = anchors.get(meter.id());
            if (anchor != null) samples.add(anchor);
            result.add(new MeterRead(meter, unique(samples), truncated));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> streamSamples(Map<String, Object> payload) {
        Object raw = payload.get("samples");
        if (raw instanceof List<?> rows) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> values) result.add((Map<String, Object>) values);
            }
            return result;
        }
        // Raw upstream page shape (content/rows/items) is accepted so the seam stays testable
        // against the customer payload without a second parser.
        Object page = payload.get("content");
        if (page instanceof Map<?, ?> nested) page = nested.get("content");
        if (page == null) page = payload.get("rows");
        if (page == null) page = payload.get("items");
        if (!(page instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> rawRow)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", rawRow.get("id"));
            values.put("equipmentId", rawRow.get("equipmentId"));
            values.put("readTime", rawRow.get("readTime"));
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("epf", rawRow.get("epf"));
            values.put("values", metrics);
            result.add(values);
        }
        return result;
    }

    private static Instant instantOrNull(String value) {
        try { return instant(value); } catch (BusinessException invalid) { return null; }
    }

    private Mono<List<MeterRead>> readAll(RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant to) {
        long deadlineAt = deadline();
        return Flux.fromIterable(meters)
                .flatMap(meter -> readMeter(identity, meter, from, to, deadlineAt), METER_CONCURRENCY)
                .collectList();
    }

    private Mono<List<MeterRead>> readAllBestEffort(
            RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant to) {
        long deadlineAt = deadline();
        return Flux.fromIterable(meters)
                .flatMap(meter -> readMeter(identity, meter, from, to, deadlineAt)
                        .onErrorResume(error -> {
                            if (error instanceof BusinessException business
                                    && (business.httpStatus() == 401 || business.httpStatus() == 403)) return Mono.error(error);
                            return Mono.just(new MeterRead(meter, List.of(), true));
                        }), METER_CONCURRENCY)
                .collectList();
    }

    private Mono<List<MeterRead>> readMeterHistory(
            RequestIdentity identity, List<AuthorizedDevice> authorized, List<AuthorizedDevice> needed,
            Instant from, Instant to, boolean bestEffort) {
        if (historySnapshots == null) {
            return bestEffort ? readAllBestEffort(identity, needed, from, to)
                    : readAll(identity, needed, from, to);
        }
        return Mono.fromCallable(() -> readMonthBlocks(identity, authorized, needed, from, to))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    if (!bestEffort || error instanceof BusinessException business
                            && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
                        return Mono.error(error);
                    }
                    // Preserve healthy meters when the shared stream is unavailable.
                    return readAllBestEffort(identity, needed, from, to);
                });
    }

    /**
     * Energy composition needs only observation fields. The readable projection is
     * reused through the guarded snapshot model for the day instead of re-reading
     * the upstream meter directory on every new outer query. Control references are
     * never persisted in this projection.
     */
    private Mono<AuthorizedDeviceScope> authorizedScope(RequestIdentity identity) {
        if (historySnapshots == null) return iot.loadAuthorizedDeviceScope(identity);
        return Mono.fromCallable(() -> loadScopeProjection(identity))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private AuthorizedDeviceScope loadScopeProjection(RequestIdentity identity) {
        ReadSnapshots.Identity snapshot = new ReadSnapshots.Identity(
                identity.tenantCode(), identity.authorization(), identity.source().name(), identity.ownerKey());
        Map<String, Object> body = historySnapshots.load(snapshot, "ENERGY_AUTH_SCOPE",
                Map.of("source", identity.source().name()), ReadSnapshots.PolicyEnum.HISTORICAL,
                () -> scopeBody(identity));
        return scopeFromBody(identity, body);
    }

    private Map<String, Object> scopeBody(RequestIdentity identity) {
        AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block();
        if (scope == null) throw new ObservationUnavailableException("公司电表授权范围为空");
        validateScope(identity, scope);
        List<Map<String, Object>> devices = new ArrayList<>();
        for (AuthorizedDevice device : scope.devices()) {
            if (!"energy".equalsIgnoreCase(device.kind())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", device.id());
            row.put("kind", device.kind());
            row.put("name", device.name());
            row.put("installAddress", device.installAddress());
            row.put("observationOnly", device.observationOnly());
            devices.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", identity.source().name());
        body.put("devices", devices);
        return body;
    }

    private static AuthorizedDeviceScope scopeFromBody(RequestIdentity identity, Map<String, Object> body) {
        Object raw = body.get("devices");
        if (!(raw instanceof List<?> rows)) {
            throw new ObservationUnavailableException("公司电表授权投影格式无效");
        }
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) {
                throw new ObservationUnavailableException("公司电表授权投影格式无效");
            }
            devices.add(new AuthorizedDevice(
                    text(row.get("id")), text(row.get("kind")), text(row.get("name")),
                    text(row.get("installAddress")), Boolean.TRUE.equals(row.get("observationOnly"))));
        }
        return new AuthorizedDeviceScopeSnapshot(identity, devices);
    }

    private long deadline() {
        return System.currentTimeMillis() + DEFAULT_READ_BUDGET_MS;
    }

    private Mono<MeterRead> readMeter(
            RequestIdentity identity, AuthorizedDevice meter, Instant from, Instant to, long deadlineAt) {
        return readPage(identity, meter, from, to, 0, new ArrayList<>(), false, 0, deadlineAt)
                .map(rows -> new MeterRead(meter, rows.rows, rows.truncated));
    }

    private Mono<PageRead> readPage(
            RequestIdentity identity, AuthorizedDevice meter, Instant from, Instant to,
            int page, List<Sample> collected, boolean truncated, int rowCount, long deadlineAt) {
        // This is background work, not a frontend deadline. Keep the sorted raw
        // boundary row: filtering it in the adapter would force an archive-wide scan.
        if (page >= maxPages || rowCount >= maxRows || System.currentTimeMillis() >= deadlineAt) {
            return Mono.just(new PageRead(collected, true));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("equipmentId", meter.id());
        params.put("page", Integer.toString(page));
        params.put("size", "500");
        return rawHistory.read(new ObservationRequest(identity, ObservationOperationEnum.ENERGY_HISTORY, params))
                .flatMap(response -> {
                    if (response.status() == ObservationStatusEnum.UNAVAILABLE) {
                        return Mono.error(new ObservationUnavailableException("计量历史上游不可用"));
                    }
                    if (response.status() != ObservationStatusEnum.AVAILABLE
                            && response.status() != ObservationStatusEnum.EMPTY
                            && response.status() != ObservationStatusEnum.PARTIAL) {
                        return Mono.error(new ObservationUnavailableException("计量历史上游返回不可用结果"));
                    }
                    boolean continuationOnly = response.payload().get("nextPage") != null
                            && response.failures().stream().allMatch(failure -> "HISTORY_PARTIAL".equals(failure.code()));
                    boolean pagePartial = response.status() == ObservationStatusEnum.PARTIAL && !continuationOnly;
                    List<Sample> pageRows = response.status() == ObservationStatusEnum.EMPTY
                            ? List.of() : parseRows(response.payload(), meter.id());
                    int nextCount = rowCount;
                    for (Sample sample : pageRows) {
                        if (!sample.readTime.isAfter(to)) {
                            collected.add(sample);
                            nextCount++;
                        }
                    }
                    boolean reachesRequestedStart = pageRows.stream().anyMatch(sample -> !sample.readTime.isAfter(from));
                    boolean last = response.status() == ObservationStatusEnum.EMPTY
                            || isLast(response.payload()) || reachesRequestedStart;
                    boolean pageTruncated = truncated || pagePartial;
                    if (last) return Mono.just(new PageRead(unique(collected), pageTruncated));
                    if (nextCount >= maxRows || page + 1 >= maxPages) {
                        return Mono.just(new PageRead(unique(collected), true));
                    }
                    return readPage(identity, meter, from, to, page + 1, collected, pageTruncated, nextCount, deadlineAt);
                })
                .onErrorMap(error -> error instanceof BusinessException
                        ? error : new ObservationUnavailableException("计量历史页读取失败"));
    }

    private Map<String, Object> dailyPayload(
            List<AuthorizedDevice> meters, List<MeterRead> reads, LocalDate start, LocalDate end, int days) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("timezone", "Asia/Shanghai");
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf"); basis.put("boundaryHour", 8); basis.put("cycle", "08:00 → 次日 08:00（采样日）");
        basis.put("unit", "kWh"); basis.put("nonOverlapping", true); basis.put("upstream", "ENERGY_HISTORY");
        payload.put("basis", basis);
        payload.put("window", Map.of("from", format(samplingBoundary(start)), "to", format(samplingBoundary(end.plusDays(1)))));
        Instant latest = latestReads(reads);
        payload.put("latestSampleTime", latest == null ? null : format(latest));
        payload.put("latestCompleteDay", null);
        boolean truncated = reads.stream().anyMatch(MeterRead::truncated);
        payload.put("truncated", truncated);
        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("status", "verified"); aggregation.put("label", "当前授权电表合计");
        aggregation.put("note", "仅使用当前授权末端电表的 epf 累计示数差"); aggregation.put("policyVersion", null);
        payload.put("aggregation", aggregation);
        List<Map<String, Object>> daysOut = new ArrayList<>();
        List<Map<String, Object>> meterOut = new ArrayList<>();
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device.id(), read);
        for (int index = 0; index < days; index++) {
            LocalDate day = start.plusDays(index);
            Instant dayFrom = samplingBoundary(day), dayTo = samplingBoundary(day.plusDays(1));
            int covered = 0;
            double sum = 0;
            for (MeterRead read : reads) {
                Double value = usage(read.samples, dayFrom, dayTo);
                if (value != null) { covered++; sum += value; }
            }
            boolean complete = covered == meters.size() && !truncated;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("usage", complete ? round(sum) : null);
            row.put("coveredMeters", covered);
            row.put("expectedMeters", meters.size());
            row.put("complete", complete);
            row.put("elapsedUsage", null);
            row.put("elapsedMeters", 0);
            daysOut.add(row);
        }
        for (int index = daysOut.size() - 1; index >= 0; index--) {
            if (Boolean.TRUE.equals(daysOut.get(index).get("complete"))) {
                payload.put("latestCompleteDay", daysOut.get(index).get("date"));
                break;
            }
        }
        for (AuthorizedDevice meter : meters) {
            MeterRead read = byId.get(meter.id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", meter.id());
            out.put("name", meter.name());
            out.put("location", meter.installAddress());
            out.put("latest", read == null || latestSample(read.samples) == null ? null : format(latestSample(read.samples)));
            out.put("samples", read == null ? 0 : read.samples.size());
            out.put("complete", read != null && !read.truncated);
            Map<String, Object> daily = new LinkedHashMap<>();
            for (int index = 0; index < days; index++) {
                LocalDate day = start.plusDays(index);
                Double value = read == null ? null : usage(read.samples, samplingBoundary(day), samplingBoundary(day.plusDays(1)));
                String status = value == null ? "not_computable" : read.truncated ? "partial" : "comparable";
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("usage", value == null ? null : round(value)); cell.put("status", status);
                daily.put(day.toString(), cell);
            }
            out.put("daily", daily);
            meterOut.add(out);
        }
        payload.put("days", daysOut);
        payload.put("meters", meterOut);
        payload.put("comparison", comparisonSlices(reads, meters, end));
        payload.put("budget", Map.of("maxPagesPerMeter", maxPages, "maxRowsPerMeter", maxRows,
                "truncated", truncated));
        return payload;
    }


    private Map<String, Object> comparisonPayload(
            AuthorizedDevice meter, MeterRead read, Instant start, Instant end, int count, int boundary, Instant now) {
        List<Map<String, Object>> periods = new ArrayList<>();
        for (int index = 0; index <= count; index++) {
            Instant from = start.plus(index * 7L, ChronoUnit.DAYS);
            Instant to = from.plus(7, ChronoUnit.DAYS);
            periods.add(comparisonPeriod(read.samples, from, to, read.truncated, format(from).substring(0, 10)));
        }
        Map<String, Object> baseline = periods.get(0);
        List<Map<String, Object>> weeks = new ArrayList<>(periods.subList(1, periods.size()));
        java.util.Collections.reverse(weeks);
        for (int index = 0; index < weeks.size(); index++) {
            Map<String, Object> current = weeks.get(index);
            Map<String, Object> previous = index + 1 < weeks.size()
                    ? weeks.get(index + 1) : baseline;
            Double previousUsage = numberOrNull(previous.get("usage"));
            Double currentUsage = numberOrNull(current.get("usage"));
            if (!"comparable".equals(current.get("status")) || currentUsage == null) {
                current.put("ratioNote", "缺少可比较的完整基期");
            } else if (!"comparable".equals(previous.get("status")) || previousUsage == null) {
                current.put("ratioNote", "缺少可比较的完整基期");
            } else if (previousUsage == 0d) {
                current.put("ratioNote", "基期为零，比例不可计算");
            } else {
                current.put("ratio", round((currentUsage - previousUsage) / previousUsage * 100));
                current.put("ratioNote", "");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-energy-comparison-" + Integer.toHexString(Objects.hash(
                meter.id(), start, end, count, boundary)));
        payload.put("type", "energy-comparison");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("status", comparisonStatus(weeks));
        payload.put("meter", Map.of("id", meter.id(), "name", meter.name(), "location", meter.installAddress()));
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf"); basis.put("fieldLabel", "反向累计示数"); basis.put("unit", null);
        basis.put("multiplier", null); basis.put("timezone", "Asia/Shanghai");
        basis.put("cycle", boundary == 8 ? "周一08:00 → 下周一08:00" : "周一00:00 → 下周一00:00");
        basis.put("boundaryHour", boundary);
        payload.put("basis", basis);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", format(start.plus(7, ChronoUnit.DAYS)));
        window.put("to", format(end));
        payload.put("window", window);
        payload.put("baseline", baseline);
        payload.put("weeks", weeks);
        payload.put("summary", comparisonSummary(weeks));
        Map<String, Object> current = null;
        if (!end.isAfter(now) && now.isBefore(end.plus(7, ChronoUnit.DAYS))) {
            Map<String, Object> live = comparisonPeriod(read.samples, end, now, read.truncated,
                    format(end).substring(0, 10));
            Map<String, Object> liveValue = new LinkedHashMap<>();
            liveValue.put("requested", Map.of("from", format(end), "to", format(now)));
            liveValue.put("partialUsage", live.get("usage"));
            Object sampledTo = live.get("sampledTo");
            liveValue.put("sampledThrough", sampledTo instanceof Map<?, ?> sample ? sample.get("readTime") : null);
            liveValue.put("comparable", false);
            current = liveValue;
        }
        payload.put("current", current);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("samples", read.samples.size());
        coverage.put("oldest", formatNullable(read.samples.isEmpty() ? null : read.samples.get(0).readTime));
        coverage.put("latest", formatNullable(latestSample(read.samples)));
        coverage.put("complete", !read.truncated);
        coverage.put("upstream", "ENERGY_HISTORY");
        coverage.put("truncated", read.truncated);
        payload.put("coverage", coverage);
        payload.put("explanation", Map.of("status", "pending"));
        return payload;
    }

    private static String comparisonStatus(List<Map<String, Object>> weeks) {
        long comparable = weeks.stream().filter(row -> "comparable".equals(row.get("status"))).count();
        if (comparable == weeks.size()) return "comparable";
        return comparable > 0 ? "partial" : "not_computable";
    }

    private static Map<String, Object> comparisonPeriod(
            List<Sample> samples, Instant from, Instant to, boolean truncated, String key) {
        List<Sample> rows = samples.stream().filter(row -> !row.readTime.isBefore(from) && !row.readTime.isAfter(to)).toList();
        Sample first = rows.isEmpty() ? null : rows.get(0);
        Sample last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        boolean conflict = false, reset = false;
        for (int index = 1; index < rows.size(); index++) {
            Sample previous = rows.get(index - 1), current = rows.get(index);
            conflict |= current.readTime.equals(previous.readTime) && current.value != previous.value;
            reset |= current.value < previous.value;
        }
        Double usage = null;
        String status = "not_computable";
        List<String> notes = new ArrayList<>();
        if (conflict) notes.add("同一时刻存在冲突示数");
        else if (reset) notes.add("累计示数回退，可能清零或换表，不计算增量");
        else if (first == null || last == null || first.readTime.equals(last.readTime)) notes.add("不足两个不同时间的有效采样");
        else {
            usage = round(last.value - first.value);
            status = first.readTime.equals(from) && last.readTime.equals(to) && !truncated ? "comparable" : "partial";
            if (!first.readTime.equals(from) || !last.readTime.equals(to)) notes.add("边界缺采样，仅显示实际缩短区间的示数增量，不估算整周");
            if (truncated) notes.add("读取预算内未核验全部采样，暂不计算环比");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("requested", Map.of("from", format(from), "to", format(to)));
        result.put("sampledFrom", sampleMap(first));
        result.put("sampledTo", sampleMap(last));
        result.put("usage", usage);
        result.put("ratio", null);
        result.put("ratioNote", "缺少可比较的完整基期");
        result.put("status", status);
        result.put("notes", notes);
        return result;
    }

    private Map<String, Object> comparisonSummary(List<Map<String, Object>> weeks) {
        List<Map<String, Object>> valid = weeks.stream().filter(row -> "comparable".equals(row.get("status"))
                && row.get("usage") instanceof Number).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comparableWeeks", valid.size());
        Map<String, Object> highest = valid.stream().max(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("week", row.get("key"), "usage", row.get("usage"))).orElse(null);
        Map<String, Object> lowest = valid.stream().min(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("week", row.get("key"), "usage", row.get("usage"))).orElse(null);
        result.put("highest", highest);
        result.put("lowest", lowest);
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int index = 1; index < weeks.size(); index++) {
            Map<String, Object> previous = weeks.get(index - 1), current = weeks.get(index);
            if (!"comparable".equals(previous.get("status")) || !"comparable".equals(current.get("status"))
                    || !(previous.get("usage") instanceof Number before)
                    || !(current.get("usage") instanceof Number after)) continue;
            changes.add(Map.of("fromWeek", previous.get("key"), "toWeek", current.get("key"),
                    "direction", after.doubleValue() > before.doubleValue() ? "up"
                            : after.doubleValue() < before.doubleValue() ? "down" : "flat"));
        }
        result.put("changes", changes);
        return result;
    }

    private Map<String, Object> monthlyPayload(
            List<AuthorizedDevice> meters, List<MeterRead> reads, List<MonthWindow> windows, int boundary) {
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device.id(), read);
        List<Map<String, Object>> meterRows = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            MeterRead read = byId.get(meter.id());
            List<Map<String, Object>> all = new ArrayList<>();
            for (MonthWindow window : windows) {
                Map<String, Object> period = comparisonPeriod(read == null ? List.of() : read.samples,
                        window.from, window.to, read == null || read.truncated, window.month.toString());
                period.put("current", window.current);
                all.add(period);
            }
            for (int index = 1; index < all.size(); index++) {
                Map<String, Object> previous = all.get(index - 1), current = all.get(index);
                if (Boolean.TRUE.equals(current.get("current")) || !"comparable".equals(previous.get("status"))
                        || !"comparable".equals(current.get("status"))) continue;
                Double before = numberOrNull(previous.get("usage")), after = numberOrNull(current.get("usage"));
                if (before == null || after == null) continue;
                if (before == 0d) current.put("ratioNote", "上期零增量，环比不可计算");
                else {
                    current.put("ratio", round((after - before) / before * 100));
                    current.put("ratioNote", "");
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("meter", Map.of("id", meter.id(), "name", meter.name(), "location", meter.installAddress()));
            boolean failed = read == null || (read.samples.isEmpty() && read.truncated);
            row.put("status", failed ? "failed" : "ready");
            row.put("includedInTotal", false);
            row.put("unit", null); row.put("multiplier", null);
            row.put("baseline", null);
            row.put("periods", new ArrayList<>(all.subList(1, all.size())));
            row.put("summary", monthlySummary(all.subList(1, all.size())));
            row.put("samples", read == null ? 0 : read.samples.size());
            row.put("complete", read != null && !read.truncated);
            row.put("latest", read == null ? null : formatNullable(latestSample(read.samples)));
            row.put("error", failed ? "没有取得有效采样" : "");
            meterRows.add(row);
        }
        List<Map<String, Object>> periods = new ArrayList<>();
        for (int index = 1; index < windows.size(); index++) {
            MonthWindow window = windows.get(index);
            Map<String, Object> period = comparisonPeriod(List.of(), window.from, window.to, true, window.month.toString());
            period.put("current", window.current);
            period.put("coveredMeters", 0);
            period.put("expectedMeters", meters.size());
            periods.add(period);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-company-energy-" + Integer.toHexString(Objects.hash(windows.get(0).from, windows.get(windows.size() - 1).to)));
        payload.put("type", "company-energy"); payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("window", Map.of("from", format(windows.get(0).from), "to", format(windows.get(windows.size() - 1).to)));
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf"); basis.put("timezone", "Asia/Shanghai"); basis.put("boundaryHour", boundary);
        basis.put("cycle", boundary == 8 ? "月初08:00 → 下月初08:00（采样月）" : "自然月");
        basis.put("upstream", "ENERGY_HISTORY");
        payload.put("basis", basis);
        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("status", "pending"); aggregation.put("label", "当前接入电表月报");
        aggregation.put("unit", null); aggregation.put("policyVersion", null);
        aggregation.put("note", "公司合计待计量集合、单位与倍率确认；逐表结果已自动读取");
        payload.put("aggregation", aggregation);
        payload.put("baseline", null); payload.put("periods", periods); payload.put("meters", meterRows);
        payload.put("truncated", reads.stream().anyMatch(MeterRead::truncated));
        long failed = meterRows.stream().filter(row -> "failed".equals(row.get("status"))).count();
        payload.put("progress", Map.of("status", failed == 0 ? "ready" : "partial", "total", meters.size(),
                "completed", meters.size() - failed, "failed", failed));
        payload.put("explanation", Map.of("status", "pending"));
        return payload;
    }

    private static Map<String, Object> monthlySummary(List<Map<String, Object>> periods) {
        List<Map<String, Object>> valid = periods.stream().filter(row -> !Boolean.TRUE.equals(row.get("current"))
                && "comparable".equals(row.get("status"))
                && row.get("usage") instanceof Number).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comparableMonths", valid.size());
        result.put("highest", valid.stream().max(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("month", row.get("key"), "usage", row.get("usage"))).orElse(null));
        result.put("lowest", valid.stream().min(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("month", row.get("key"), "usage", row.get("usage"))).orElse(null));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int index = 1; index < periods.size(); index++) {
            Map<String, Object> previous = periods.get(index - 1), current = periods.get(index);
            if (Boolean.TRUE.equals(current.get("current"))
                    || !"comparable".equals(previous.get("status")) || !"comparable".equals(current.get("status"))
                    || !(previous.get("usage") instanceof Number before)
                    || !(current.get("usage") instanceof Number after)) continue;
            changes.add(Map.of("fromMonth", previous.get("key"), "toMonth", current.get("key"),
                    "direction", after.doubleValue() > before.doubleValue() ? "up"
                            : after.doubleValue() < before.doubleValue() ? "down" : "flat"));
        }
        result.put("changes", changes);
        return result;
    }

    private static Double numberOrNull(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }


    private static Map<String, String> otherScopeParams(Map<String, String> p) {
        Map<String, String> shadow = new LinkedHashMap<>();
        for (var entry : Map.of("otherScopeLevel", "scopeLevel", "otherFloor", "floor",
                "otherText", "text", "otherMeterId", "meterId", "otherMeterIds", "meterIds").entrySet()) {
            String value = p.get(entry.getKey());
            if (value != null) shadow.put(entry.getValue(), value);
        }
        return shadow;
    }


    private Map<String, Object> baseReport(ObservationRequest request, List<AuthorizedDevice> meters,
            List<MeterRead> reads, Instant from, Instant to, String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", java.util.UUID.randomUUID().toString());
        payload.put("type", "meter-report");
        payload.put("kind", kind);
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("from", formatNullable(earliest(reads))); observed.put("to", formatNullable(latestReads(reads)));
        span.put("observed", observed);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("span", span); params.put("scope", scopeParam(request, meters)); params.put("grain", p(request, "grain", "day"));
        payload.put("params", params);
        payload.put("coverage", coverage(meters, reads));
        payload.put("authorizationMeterIds", meters.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    private Map<String, Object> scopeParam(Map<String, String> parameters, List<AuthorizedDevice> meters) {
        String level = parameters.getOrDefault("scopeLevel", "all");
        String label = switch (level) {
            case "location" -> meters.get(0).installAddress().isBlank() ? meters.get(0).name() : meters.get(0).installAddress();
            case "floor" -> parameters.getOrDefault("floor", "");
            case "selection" -> "所选" + meters.size() + "台电表";
            case "nameMatch" -> "按名称匹配「" + parameters.getOrDefault("text", "") + "」";
            default -> "全部授权电表";
        };
        String basis = "location".equals(level) || "selection".equals(level) ? "deviceIds" : "nameMatch".equals(level) ? "nameMatch" : "areaName";
        return Map.of("level", level, "label", label, "basis", basis, "meterCount", meters.size());
    }
    private Map<String, Object> scopeParam(ObservationRequest request, List<AuthorizedDevice> meters) {
        return scopeParam(request.parameters(), meters);
    }


    private Map<String, Object> coverage(List<AuthorizedDevice> meters, List<MeterRead> reads) {
        List<String> noData = new ArrayList<>();
        List<Map<String, Object>> partial = new ArrayList<>();
        Instant latest = latestReads(reads);
        for (MeterRead read : reads) {
            if (read.samples.isEmpty()) noData.add(read.device.id());
            else if (read.truncated) partial.add(Map.of("id", read.device.id(), "through", formatNullable(latestSample(read.samples))));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meters", meters.size());
        result.put("dataMeters", (int) reads.stream().filter(r -> !r.samples.isEmpty()).count());
        result.put("noData", noData); result.put("partial", partial);
        result.put("truncated", reads.stream().anyMatch(MeterRead::truncated));
        result.put("latestSampleAt", formatNullable(latest));
        return result;
    }


    private Map<String, Object> comparisonSlices(List<MeterRead> reads, List<AuthorizedDevice> meters, LocalDate end) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : Map.of("currentDay", 0, "previousDay", -1, "lastWeekSameDay", -7).entrySet()) {
            LocalDate day = end.plusDays(entry.getValue());
            Instant from = samplingBoundary(day), to = samplingBoundary(day.plusDays(1));
            Map<String, Double> usage = new LinkedHashMap<>();
            int covered = 0;
            for (MeterRead read : reads) {
                Double value = usage(read.samples, from, to);
                if (value != null && !read.truncated) { usage.put(read.device.id(), round(value)); covered++; }
            }
            Map<String, Object> slice = new LinkedHashMap<>();
            slice.put("date", day.toString()); slice.put("from", format(from)); slice.put("to", format(to));
            slice.put("usage", covered == meters.size() ? round(usage.values().stream().mapToDouble(Double::doubleValue).sum()) : null);
            slice.put("coveredMeters", covered); slice.put("expectedMeters", meters.size()); slice.put("complete", covered == meters.size());
            slice.put("meterUsage", usage); result.put(entry.getKey(), slice);
        }
        return result;
    }

    private static List<Sample> parseRows(Map<String, Object> payload, String requestedMeter) {
        Object raw = payload.get("content");
        if (raw instanceof Map<?, ?> map) raw = map.get("content");
        if (raw == null) raw = payload.get("rows");
        if (raw == null) raw = payload.get("items");
        if (raw == null) raw = payload.get("samples");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> rows)) throw new IllegalArgumentException("计量历史内容不是数组");
        List<Sample> result = new ArrayList<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> rawMap)) throw new IllegalArgumentException("计量历史行格式无效");
            Map<?, ?> row = rawMap;
            String meterId = text(row.get("equipmentId"));
            if (meterId == null || !requestedMeter.equals(meterId)) continue;
            String readTime = text(row.get("readTime"));
            if (readTime == null || text(row.get("id")) == null) throw new IllegalArgumentException("计量历史行缺少身份或时间");
            Object rawValue = row.get("epf");
            if (rawValue == null && row.get("values") instanceof Map<?, ?> values) rawValue = values.get("epf");
            if (rawValue == null) continue;
            double value = number(rawValue);
            result.add(new Sample(text(row.get("id")), instant(readTime), value, numberOrNull(row.get("totalPowerFactor"))));
        }
        return result;
    }

    private static boolean isLast(Map<String, Object> payload) {
        Object value = payload.get("last");
        if (value instanceof Boolean last) return last;
        value = payload.get("hasNext");
        if (value instanceof Boolean next) return !next;
        Object nextPage = payload.get("nextPage");
        return nextPage == null;
    }

    private static List<Sample> unique(List<Sample> rows) {
        Map<String, Sample> result = new LinkedHashMap<>();
        for (Sample row : rows) result.put(row.id + ":" + row.readTime, row);
        return result.values().stream().sorted(Comparator.comparing(Sample::readTime)).toList();
    }

    private static Double usage(List<Sample> samples, Instant from, Instant to) {
        Sample start = null, end = null;
        for (Sample sample : samples) {
            if (!sample.readTime.isAfter(from)) start = sample;
            if (!sample.readTime.isAfter(to)) end = sample;
        }
        if (start == null) {
            for (Sample sample : samples) if (sample.readTime.isAfter(from)
                    && sample.readTime.isBefore(from.plusSeconds(TWELVE_HOURS_SECONDS))) { start = sample; break; }
        }
        if (start == null || end == null || !start.readTime.isBefore(end.readTime)) return null;
        Instant lastTime = null; double lastValue = 0;
        for (Sample sample : samples) {
            if (sample.readTime.isBefore(start.readTime) || sample.readTime.isAfter(end.readTime)) continue;
            if (lastTime != null && (!sample.readTime.isAfter(lastTime) || sample.value < lastValue)) return null;
            lastTime = sample.readTime; lastValue = sample.value;
        }
        double value = end.value - start.value;
        return value >= 0 && Double.isFinite(value) ? value : null;
    }


    private static Map<String, Object> sampleMap(Sample sample) {
        if (sample == null) return null;
        return Map.of("readTime", format(sample.readTime), "value", sample.value);
    }

    private static List<AuthorizedDevice> energyMeters(AuthorizedDeviceScope scope) {
        return scope.devices().stream().filter(device -> "energy".equalsIgnoreCase(device.kind())).toList();
    }

    private static List<AuthorizedDevice> selectMeters(List<AuthorizedDevice> meters, Map<String, String> p) {
        String level = p.getOrDefault("scopeLevel", "all");
        if ("all".equals(level)) return meters;
        if ("location".equals(level)) return meters.stream().filter(m -> m.id().equals(p.get("meterId"))).toList();
        if ("selection".equals(level)) {
            Set<String> ids = splitIds(p.get("meterIds"));
            List<AuthorizedDevice> selected = meters.stream().filter(m -> ids.contains(m.id())).toList();
            if (ids.isEmpty() || selected.size() != ids.size()) {
                throw new BusinessException("FORBIDDEN", "所选计量范围不在当前授权范围内", 403);
            }
            return selected;
        }
        if ("floor".equals(level)) return meters.stream().filter(m -> Objects.equals(floor(m.installAddress()), p.get("floor"))).toList();
        if ("nameMatch".equals(level)) {
            String text = p.getOrDefault("text", "");
            return meters.stream().filter(m -> (m.name() + " " + m.installAddress()).contains(text)
                    && (p.get("floor") == null || Objects.equals(floor(m.installAddress()), p.get("floor")))).toList();
        }
        throw new IllegalArgumentException("计量范围无效");
    }

    private static Set<String> splitIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = value.replace('[', ' ').replace(']', ' ').replace('"', ' ');
        Set<String> ids = new LinkedHashSet<>();
        for (String id : normalized.split(",")) if (!id.trim().isEmpty()) ids.add(id.trim());
        return ids;
    }

    private static void validateScope(RequestIdentity identity, AuthorizedDeviceScope scope) {
        if (!identity.equals(scope.identity()) || scope.source() != IotSourceEnum.COMPANY) {
            throw new ObservationUnavailableException("公司电表授权范围与当前请求不匹配");
        }
    }

    private static void requireCompany(RequestIdentity identity) {
        if (identity.source() != IotSourceEnum.COMPANY) throw new ObservationUnavailableException("公司能耗仅支持公司来源");
    }

    private static LocalDate date(String value) {
        try { return LocalDate.parse(value); } catch (RuntimeException error) { throw new ObservationUnavailableException("能耗日期无效"); }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) throw new ObservationUnavailableException("能耗时间窗口缺失");
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException ignored) {
            try { return LocalDateTime.parse(value, LOCAL_DATE_TIME).toInstant(SHANGHAI_OFFSET); }
            catch (RuntimeException error) { throw new ObservationUnavailableException("能耗时间格式无效"); }
        }
    }

    private static String format(Instant value) {
        return value.atZone(SHANGHAI).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String formatNullable(Instant value) { return value == null ? null : format(value); }

    private static Zoned local(LocalDate value) { return new Zoned(value.atStartOfDay(SHANGHAI)); }

    private static Instant samplingBoundary(LocalDate value) {
        return value.atTime(8, 0).atZone(SHANGHAI).toInstant();
    }


    private static String floor(String address) {
        if (address == null) return null;
        int index = address.indexOf('楼');
        if (index < 1) return null;
        int start = index - 1;
        while (start > 0 && Character.isDigit(address.charAt(start - 1))) start--;
        return address.substring(start, index + 1);
    }

    private static int integer(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try { int parsed = Integer.parseInt(value); if (parsed >= min && parsed <= max) return parsed; }
        catch (NumberFormatException ignored) { }
        throw new ObservationUnavailableException("能耗窗口参数无效");
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw new ObservationUnavailableException("缺少" + key); return value;
    }

    private static String p(ObservationRequest request, String key, String fallback) { return request.parameters().getOrDefault(key, fallback); }

    private static double number(Object value) {
        try {
            double parsed = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException error) { throw new IllegalArgumentException("累计示数格式无效"); }
    }

    private static String text(Object value) { return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value); }

    private static Instant latestReads(List<MeterRead> reads) { return reads.stream().map(read -> latestSample(read.samples)).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null); }
    private static Instant earliest(List<MeterRead> reads) { return reads.stream().map(read -> read.samples.isEmpty() ? null : read.samples.get(0).readTime).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null); }
    private static Instant latestSample(List<Sample> samples) { return samples.isEmpty() ? null : samples.get(samples.size() - 1).readTime; }
    private static int dayCount(Instant from, Instant to) { return Math.max(1, (int) Math.ceil((to.toEpochMilli() - from.toEpochMilli()) / 86_400_000d)); }
    private static double round(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue(); }

    private record ReportWindow(Instant from, Instant to, String label) { }
    private record MonthWindow(YearMonth month, Instant from, Instant to, boolean current) { }

    record Sample(String id, Instant readTime, double value, Double powerFactor) { }
    record MeterRead(AuthorizedDevice device, List<Sample> samples, boolean truncated) { }
    private record PageRead(List<Sample> rows, boolean truncated) { }
    private record Zoned(java.time.ZonedDateTime value) {
        Instant toInstant() { return value.toInstant(); }
    }
}
