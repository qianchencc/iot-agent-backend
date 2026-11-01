package ltd.qianc.iotagent.iot.internal;

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
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Local Company energy projection. No state is retained and no upstream URL is inferred. */
public final class CompanyEnergyObservationComposerImpl implements CompanyEnergyObservationComposer {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneOffset SHANGHAI_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_MAX_PAGES = 60;
    private static final int DEFAULT_MAX_ROWS = 1_200;
    private static final int MAX_DAYS = 90;
    private static final long DAY_SECONDS = 86_400L;
    private static final long TWELVE_HOURS_SECONDS = 43_200L;

    private final ObservationAdapter rawHistory;
    private final IotAdapter iot;
    private final Clock clock;
    private final int maxPages;
    private final int maxRows;

    public CompanyEnergyObservationComposerImpl(ObservationAdapter rawHistory, IotAdapter iot, Clock clock) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, int maxPages, int maxRows) {
        this.rawHistory = Objects.requireNonNull(rawHistory, "rawHistory");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPages < 1 || maxPages > 1_000) throw new IllegalArgumentException("maxPages must be 1..1000");
        if (maxRows < 1 || maxRows > 100_000) throw new IllegalArgumentException("maxRows must be 1..100000");
        this.maxPages = maxPages;
        this.maxRows = maxRows;
    }

    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            requireCompany(request.identity());
            if (request.operation() != ObservationOperation.COMPANY_ENERGY_DAILY
                    && request.operation() != ObservationOperation.COMPANY_METER_REPORT) {
                return Mono.error(new ObservationUnavailableException("公司能耗组合器不支持该观察操作"));
            }
            return iot.loadAuthorizedDeviceScope(request.identity())
                    .switchIfEmpty(Mono.error(new ObservationUnavailableException("公司电表授权范围为空")))
                    .flatMap(scope -> {
                        validateScope(request.identity(), scope);
                        List<AuthorizedDevice> meters = energyMeters(scope);
                        if (meters.isEmpty()) return Mono.error(new BusinessException("FORBIDDEN", "当前来源没有公司电表观察范围", 403));
                        if (request.operation() == ObservationOperation.COMPANY_ENERGY_DAILY) {
                            return daily(request, meters);
                        }
                        return meterReport(request, meters);
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
        Instant from = local(start).toInstant().minusSeconds(TWELVE_HOURS_SECONDS);
        Instant to = local(end.plusDays(1)).toInstant();
        return readAll(request.identity(), meters, from, to)
                .map(reads -> energyResponse(dailyPayload(meters, reads, start, end, days)));
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
        Instant baseFrom = null, baseTo = null;
        if (comparison) {
            baseFrom = p.containsKey("baseFrom") ? instant(p.get("baseFrom")) : from.minus(to.toEpochMilli() - from.toEpochMilli(), ChronoUnit.MILLIS);
            baseTo = p.containsKey("baseTo") ? instant(p.get("baseTo")) : from;
            if (!baseFrom.isBefore(baseTo) || baseTo.isAfter(clock.instant())) {
                return Mono.error(new ObservationUnavailableException("计量对比时间窗口无效"));
            }
        }
        Instant readFrom = from.minusSeconds(TWELVE_HOURS_SECONDS);
        Instant readTo = comparison ? (baseTo.isAfter(to) ? baseTo : to) : to;
        Instant finalBaseFrom = baseFrom, finalBaseTo = baseTo;
        return readAll(request.identity(), selected, readFrom, readTo)
                .map(reads -> comparison
                        ? energyResponse(comparisonPayload(request, selected, reads, from, to, finalBaseFrom, finalBaseTo))
                        : energyResponse(statisticsPayload(request, selected, reads, from, to)));
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

    private Mono<List<MeterRead>> readAll(RequestIdentity identity, List<AuthorizedDevice> meters, Instant from, Instant to) {
        return Flux.fromIterable(meters)
                .flatMap(meter -> readMeter(identity, meter, from, to), 6)
                .collectList();
    }

    private Mono<MeterRead> readMeter(RequestIdentity identity, AuthorizedDevice meter, Instant from, Instant to) {
        return readPage(identity, meter, from, to, 0, new ArrayList<>(), false, 0)
                .map(rows -> new MeterRead(meter, rows.rows, rows.truncated));
    }

    private Mono<PageRead> readPage(
            RequestIdentity identity, AuthorizedDevice meter, Instant from, Instant to,
            int page, List<Sample> collected, boolean truncated, int rowCount) {
        if (page >= maxPages || rowCount >= maxRows) {
            return Mono.just(new PageRead(collected, true));
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("equipmentId", meter.id());
        params.put("from", format(from));
        params.put("to", format(to));
        params.put("page", Integer.toString(page));
        params.put("size", "100");
        return rawHistory.read(new ObservationRequest(identity, ObservationOperation.ENERGY_HISTORY, params))
                .flatMap(response -> {
                    if (response.status() == ObservationStatus.UNAVAILABLE) {
                        return Mono.error(new ObservationUnavailableException("计量历史上游不可用"));
                    }
                    if (response.status() != ObservationStatus.AVAILABLE
                            && response.status() != ObservationStatus.EMPTY
                            && response.status() != ObservationStatus.PARTIAL) {
                        return Mono.error(new ObservationUnavailableException("计量历史上游返回不可用结果"));
                    }
                    boolean pagePartial = response.status() == ObservationStatus.PARTIAL;
                    List<Sample> pageRows = response.status() == ObservationStatus.EMPTY
                            ? List.of() : parseRows(response.payload(), meter.id());
                    int nextCount = rowCount + pageRows.size();
                    collected.addAll(pageRows);
                    boolean reachesRequestedStart = pageRows.stream().anyMatch(sample -> !sample.readTime.isAfter(from));
                    boolean last = response.status() == ObservationStatus.EMPTY
                            || isLast(response.payload()) || reachesRequestedStart;
                    boolean pageTruncated = truncated || pagePartial;
                    if (last) return Mono.just(new PageRead(unique(collected), pageTruncated));
                    if (nextCount >= maxRows || page + 1 >= maxPages) {
                        return Mono.just(new PageRead(unique(collected), true));
                    }
                    return readPage(identity, meter, from, to, page + 1, collected, pageTruncated, nextCount);
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
        payload.put("window", Map.of("from", format(local(start).toInstant()), "to", format(local(end.plusDays(1)).toInstant())));
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
            Instant dayFrom = local(day).toInstant(), dayTo = local(day.plusDays(1)).toInstant();
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
                Double value = read == null ? null : usage(read.samples, local(day).toInstant(), local(day.plusDays(1)).toInstant());
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

    private Map<String, Object> statisticsPayload(
            ObservationRequest request, List<AuthorizedDevice> meters, List<MeterRead> reads, Instant from, Instant to) {
        Map<String, Object> payload = baseReport(request, meters, reads, from, to, "statistics");
        List<Map<String, Object>> meterRows = reportMeters(meters, reads, from, to);
        int dataMeters = (int) meterRows.stream().filter(row -> "ok".equals(row.get("status"))).count();
        Double total = sumValues(meterRows);
        List<Map<String, Object>> buckets = buckets(meters, reads, from, to);
        Map<String, Object> totalView = new LinkedHashMap<>();
        totalView.put("value", total); totalView.put("unit", "度"); totalView.put("yuan", null); totalView.put("tariff", null);
        payload.put("total", totalView);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("value", total); summary.put("unit", "度"); summary.put("dataMeters", dataMeters);
        summary.put("meters", meters.size()); summary.put("partial", dataMeters != meters.size());
        payload.put("summary", summary);
        payload.put("meters", meterRows);
        payload.put("buckets", Map.of("grain", p(request, "grain", "day"), "items", buckets));
        payload.put("bucketsAll", Map.of("day", buckets, "hour", List.of(), "week", List.of(), "month", List.of()));
        payload.put("composition", composition(meterRows));
        payload.put("metrics", List.of(metric("dayTotal", total, "度"),
                metric("dailyAvg", total == null ? null : total / Math.max(1, dayCount(from, to)), "度/日")));
        Map<String, Object> shape24 = new LinkedHashMap<>(); shape24.put("rates", null); shape24.put("coveredFraction", 0);
        payload.put("shape24", shape24);
        return payload;
    }

    private Map<String, Object> comparisonPayload(
            ObservationRequest request, List<AuthorizedDevice> meters, List<MeterRead> reads,
            Instant from, Instant to, Instant baseFrom, Instant baseTo) {
        Map<String, Object> payload = baseReport(request, meters, reads, from, to, "comparison");
        List<Map<String, Object>> aMeters = reportMeters(meters, reads, from, to);
        List<Map<String, Object>> bMeters = reportMeters(meters, reads, baseFrom, baseTo);
        Double aTotal = sumValues(aMeters), bTotal = sumValues(bMeters);
        List<Map<String, Object>> comparisonMeters = new ArrayList<>();
        Map<String, Map<String, Object>> bById = index(bMeters);
        for (Map<String, Object> a : aMeters) {
            Map<String, Object> b = bById.get(a.get("id"));
            Map<String, Object> comparisonMeter = new LinkedHashMap<>();
            comparisonMeter.put("id", a.get("id")); comparisonMeter.put("name", a.get("name"));
            comparisonMeter.put("areaName", a.get("areaName")); comparisonMeter.put("floor", a.get("floor"));
            comparisonMeter.put("a", a);
            if (b == null) {
                Map<String, Object> empty = new LinkedHashMap<>(); empty.put("value", null); empty.put("status", "noData");
                comparisonMeter.put("b", empty);
            } else comparisonMeter.put("b", b);
            comparisonMeters.add(comparisonMeter);
        }
        Map<String, Object> sideA = side(aTotal, aMeters, buckets(meters, reads, from, to));
        Map<String, Object> sideB = side(bTotal, bMeters, buckets(meters, reads, baseFrom, baseTo));
        Double diff = aTotal == null || bTotal == null ? null : round(aTotal - bTotal);
        payload.put("params", comparisonParams(request, meters, from, to, baseFrom, baseTo));
        payload.put("sides", Map.of("a", sideA, "b", sideB));
        Map<String, Object> diffView = new LinkedHashMap<>();
        diffView.put("value", diff);
        diffView.put("pct", bTotal == null || bTotal == 0 || diff == null ? null : round(diff / bTotal * 100));
        if (bTotal == null) diffView.put("pctNote", "缺少可比较的基期数据");
        else if (bTotal == 0) diffView.put("pctNote", "基期为零，比例不可计算");
        diffView.put("basis", "total"); diffView.put("unit", "度");
        payload.put("diff", diffView);
        payload.put("meters", comparisonMeters);
        payload.put("coverage", coverage(meters, reads));
        Map<String, Object> contribution = new LinkedHashMap<>();
        contribution.put("rows", List.of()); contribution.put("rest", null); contribution.put("topShare", null); contribution.put("unit", "度");
        payload.put("contribution", contribution);
        return payload;
    }

    private Map<String, Object> baseReport(ObservationRequest request, List<AuthorizedDevice> meters,
            List<MeterRead> reads, Instant from, Instant to, String kind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-energy-" + Integer.toHexString(Objects.hash(request.identity().ownerKey(), from, to, kind)));
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

    private Map<String, Object> scopeParam(ObservationRequest request, List<AuthorizedDevice> meters) {
        String level = request.parameters().getOrDefault("scopeLevel", "all");
        String label = switch (level) {
            case "location" -> meters.get(0).installAddress().isBlank() ? meters.get(0).name() : meters.get(0).installAddress();
            case "floor" -> request.parameters().getOrDefault("floor", "");
            case "nameMatch" -> "按名称匹配「" + request.parameters().getOrDefault("text", "") + "」";
            default -> "全部授权电表";
        };
        String basis = "location".equals(level) || "selection".equals(level) ? "deviceIds" : "nameMatch".equals(level) ? "nameMatch" : "areaName";
        return Map.of("level", level, "label", label, "basis", basis, "meterCount", meters.size());
    }

    private Map<String, Object> comparisonParams(ObservationRequest request, List<AuthorizedDevice> meters,
            Instant from, Instant to, Instant baseFrom, Instant baseTo) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("axis", "time");
        result.put("span", Map.of("requested", Map.of("from", format(from), "to", format(to)), "observed", Map.of()));
        result.put("baseline", Map.of("requested", Map.of("from", format(baseFrom), "to", format(baseTo)), "observed", Map.of()));
        result.put("scope", scopeParam(request, meters));
        return result;
    }

    private List<Map<String, Object>> reportMeters(List<AuthorizedDevice> meters, List<MeterRead> reads, Instant from, Instant to) {
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device.id(), read);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            MeterRead read = byId.get(meter.id());
            Window window = read == null ? Window.empty() : window(read.samples, from, to);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", meter.id());
            row.put("name", meter.name());
            row.put("areaName", meter.installAddress());
            row.put("floor", floor(meter.installAddress()));
            row.put("value", window.value);
            row.put("status", window.status(read == null || read.truncated));
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("fromSample", sampleMap(window.from)); observed.put("toSample", sampleMap(window.to));
            row.put("observed", observed);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> buckets(List<AuthorizedDevice> meters, List<MeterRead> reads, Instant from, Instant to) {
        List<Map<String, Object>> result = new ArrayList<>();
        Instant cursor = nextShanghaiEight(from);
        while (cursor.plusSeconds(DAY_SECONDS).compareTo(to) <= 0) {
            Instant bucketTo = cursor.plusSeconds(DAY_SECONDS);
            double sum = 0;
            int complete = 0;
            for (MeterRead read : reads) {
                Double value = usage(read.samples, cursor, bucketTo);
                if (value != null && !read.truncated) { sum += value; complete++; }
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(cursor));
            bucket.put("to", format(bucketTo));
            bucket.put("value", complete == meters.size() ? round(sum) : null);
            bucket.put("partial", complete != meters.size());
            bucket.put("days", 1);
            result.add(bucket);
            cursor = bucketTo;
        }
        return result;
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

    private Map<String, Object> composition(List<Map<String, Object>> meters) {
        Map<String, Double> sums = new HashMap<>();
        for (Map<String, Object> meter : meters) if (meter.get("value") instanceof Number value) {
            String floor = String.valueOf(meter.get("floor"));
            sums.merge(floor, value.doubleValue(), Double::sum);
        }
        List<Map<String, Object>> rows = sums.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> Map.<String, Object>of("label", entry.getKey(), "value", round(entry.getValue()), "meterIds", List.of()))
                .toList();
        Map<String, Object> excluded = new LinkedHashMap<>();
        excluded.put("noData", 0); excluded.put("partial", 0); excluded.put("through", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "ranking"); result.put("rows", rows); result.put("rest", null); result.put("excluded", excluded);
        return result;
    }

    private Map<String, Object> side(Double totalValue, List<Map<String, Object>> rows, List<Map<String, Object>> buckets) {
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("value", totalValue); total.put("unit", "度"); total.put("yuan", null);
        Map<String, Object> shape = new LinkedHashMap<>(); shape.put("rates", null); shape.put("coveredFraction", 0);
        return Map.of("total", total, "dataMeters",
                (int) rows.stream().filter(row -> "ok".equals(row.get("status"))).count(), "effectiveDays", buckets.size(),
                "shape24", shape, "buckets", Map.of("grain", "day", "items", buckets));
    }

    private Map<String, Object> comparisonSlices(List<MeterRead> reads, List<AuthorizedDevice> meters, LocalDate end) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : Map.of("currentDay", 0, "previousDay", -1, "lastWeekSameDay", -7).entrySet()) {
            LocalDate day = end.plusDays(entry.getValue());
            Instant from = local(day).toInstant(), to = local(day.plusDays(1)).toInstant();
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
            result.add(new Sample(text(row.get("id")), instant(readTime), value));
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

    private static Window window(List<Sample> samples, Instant from, Instant to) {
        Sample start = null, end = null;
        for (Sample sample : samples) {
            if (!sample.readTime.isAfter(from)) start = sample;
            if (!sample.readTime.isAfter(to)) end = sample;
        }
        Double value = usage(samples, from, to);
        return new Window(start, end, value);
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
            return meters.stream().filter(m -> ids.contains(m.id())).toList();
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
        if (!identity.equals(scope.identity()) || scope.source() != IotSource.COMPANY) {
            throw new ObservationUnavailableException("公司电表授权范围与当前请求不匹配");
        }
    }

    private static void requireCompany(RequestIdentity identity) {
        if (identity.source() != IotSource.COMPANY) throw new ObservationUnavailableException("公司能耗仅支持公司来源");
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

    private static Instant nextShanghaiEight(Instant value) {
        var local = value.atZone(SHANGHAI).toLocalDate().atTime(java.time.LocalTime.of(8, 0));
        Instant candidate = local.atZone(SHANGHAI).toInstant();
        return candidate.isAfter(value) ? candidate : candidate.plusSeconds(DAY_SECONDS);
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
    private static Double sumValues(List<Map<String, Object>> rows) { double sum = 0; int count = 0; for (Map<String, Object> row : rows) if (row.get("value") instanceof Number value) { sum += value.doubleValue(); count++; } return count == 0 ? null : round(sum); }
    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> rows) { Map<String, Map<String, Object>> out = new HashMap<>(); for (Map<String, Object> row : rows) out.put(String.valueOf(row.get("id")), row); return out; }
    private static Map<String, Object> metric(String key, Double value, String unit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key); result.put("value", value); result.put("unit", unit);
        return result;
    }
    private static double round(double value) { return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue(); }

    private record Sample(String id, Instant readTime, double value) { }
    private record MeterRead(AuthorizedDevice device, List<Sample> samples, boolean truncated) { }
    private record PageRead(List<Sample> rows, boolean truncated) { }
    private record Window(Sample from, Sample to, Double value) {
        static Window empty() { return new Window(null, null, null); }
        String status(boolean truncated) { return value == null ? (from == null && to == null ? "noData" : "partial") : truncated ? "partial" : "ok"; }
    }
    private record Zoned(java.time.ZonedDateTime value) {
        Instant toInstant() { return value.toInstant(); }
    }
}
