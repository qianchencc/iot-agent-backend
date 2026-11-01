package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposer;
import ltd.qianc.iotagent.iot.CompanyEnergyPolicy;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationFailure;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/**
 * Company energy business projection.
 *
 * <p>All values are derived from the current authorized meter catalog and the
 * customer sample stream. Metering days are Shanghai 08:00 → next 08:00; a
 * missing boundary is never zero-filled. The same interval arithmetic feeds
 * daily, statistics, time/region comparisons, night windows and report
 * evidence, so the page and the dialogue cannot drift apart.</p>
 */
public final class CompanyEnergyObservationComposerImpl implements CompanyEnergyObservationComposer {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int DEFAULT_MAX_PAGES = 60;
    private static final int DEFAULT_MAX_ROWS = 30_000;
    private static final int PAGE_SIZE = 500;
    private static final long DAY_MS = 86_400_000L;
    private static final long HOUR_MS = 3_600_000L;
    private static final long MAX_GAP_MS = 6 * HOUR_MS;
    private static final long LOOKBACK_MS = 7 * DAY_MS;
    private static final long LOOKAHEAD_MS = 2 * DAY_MS;
    private static final long READ_BUDGET_MS = 25_000L;
    private static final int MAX_DAILY_DAYS = 90;
    private static final int MAX_BUCKETS = 400;
    private static final List<String> GRAINS = List.of("hour", "day", "week", "month");

    private final ObservationAdapter rawHistory;
    private final IotAdapter iot;
    private final Clock clock;
    private final int maxPages;
    private final int maxRows;
    private final CompanyEnergyPolicy policy;

    public CompanyEnergyObservationComposerImpl(ObservationAdapter rawHistory, IotAdapter iot, Clock clock) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, null);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, CompanyEnergyPolicy policy) {
        this(rawHistory, iot, clock, DEFAULT_MAX_PAGES, DEFAULT_MAX_ROWS, policy);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock, int maxPages, int maxRows) {
        this(rawHistory, iot, clock, maxPages, maxRows, null);
    }

    public CompanyEnergyObservationComposerImpl(
            ObservationAdapter rawHistory, IotAdapter iot, Clock clock,
            int maxPages, int maxRows, CompanyEnergyPolicy policy) {
        this.rawHistory = Objects.requireNonNull(rawHistory, "rawHistory");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPages < 1 || maxPages > 1_000) throw new IllegalArgumentException("maxPages must be 1..1000");
        if (maxRows < 1 || maxRows > 1_000_000) throw new IllegalArgumentException("maxRows must be 1..1000000");
        this.maxPages = maxPages;
        this.maxRows = maxRows;
        this.policy = policy;
    }

    @Override
    public Mono<ObservationResponse> readEnergyReport(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            requireCompany(request.identity());
            if (request.operation() != ObservationOperation.ENERGY_REPORT) {
                return Mono.error(new ObservationUnavailableException("公司能耗组合器不支持该观察操作"));
            }
            return loadMeters(request.identity()).flatMap(meters -> energyReport(request, meters));
        });
    }

    @Override
    public Mono<ObservationResponse> read(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        return Mono.defer(() -> {
            requireCompany(request.identity());
            if (request.operation() != ObservationOperation.COMPANY_ENERGY_DAILY
                    && request.operation() != ObservationOperation.ENERGY_COMPARISON
                    && request.operation() != ObservationOperation.COMPANY_ENERGY_REPORT
                    && request.operation() != ObservationOperation.COMPANY_METER_REPORT) {
                return Mono.error(new ObservationUnavailableException("公司能耗组合器不支持该观察操作"));
            }
            return loadMeters(request.identity()).flatMap(meters -> switch (request.operation()) {
                case COMPANY_ENERGY_DAILY -> daily(request, meters);
                case ENERGY_COMPARISON -> weeklyComparison(request, meters);
                case COMPANY_ENERGY_REPORT -> monthlyReport(request, meters);
                default -> meterReport(request, meters);
            });
        }).onErrorMap(error -> error instanceof BusinessException
                ? error : new ObservationUnavailableException("公司电量读取失败"));
    }

    private Mono<List<AuthorizedDevice>> loadMeters(RequestIdentity identity) {
        return iot.loadAuthorizedDeviceScope(identity)
                .switchIfEmpty(Mono.error(new ObservationUnavailableException("公司电表授权范围为空")))
                .flatMap(scope -> {
                    validateScope(identity, scope);
                    List<AuthorizedDevice> meters = energyMeters(scope);
                    if (meters.isEmpty()) {
                        return Mono.error(new BusinessException("FORBIDDEN", "当前来源没有公司电表观察范围", 403));
                    }
                    return Mono.just(meters);
                });
    }

    // ------------------------------------------------------------------
    // Daily: Shanghai 08:00 metering days for the selected window.
    // ------------------------------------------------------------------

    private Mono<ObservationResponse> daily(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        int days = integer(p.get("days"), 30, 1, MAX_DAILY_DAYS);
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate requestedEnd = p.containsKey("end") ? date(p.get("end")) : null;
        if (requestedEnd != null && requestedEnd.isAfter(today)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "结束日期不能晚于今天", 400));
        }
        LocalDate streamEnd = requestedEnd != null ? requestedEnd : today;
        LocalDate streamStart = streamEnd.minusDays(MAX_DAILY_DAYS);
        Instant stopBefore = boundary(streamStart).minusMillis(LOOKBACK_MS);
        Instant endAt = boundary(today.plusDays(1));
        return readStream(request.identity(), ids(meters), stopBefore, endAt).map(stream -> {
            LocalDate end = requestedEnd;
            if (end == null) {
                Instant latest = stream.latest();
                if (latest == null) {
                    end = today.minusDays(1);
                } else {
                    LocalDate latestDate = dateOf(latest);
                    LocalTime latestTime = latest.atZone(SHANGHAI).toLocalTime();
                    // Metering day D ends at D+1 08:00. A sample before 08:00 belongs
                    // to the day that has not ended yet, so the latest complete day is
                    // two calendar days back until the 08:00 boundary is crossed.
                    end = latestTime.isBefore(LocalTime.of(8, 0))
                            ? latestDate.minusDays(2) : latestDate.minusDays(1);
                }
                if (end.isAfter(today.minusDays(1))) end = today.minusDays(1);
            }
            LocalDate start = end.minusDays(days - 1L);
            boolean adjusted = requestedEnd != null && requestedEnd.isAfter(end);
            return energyResponse(dailyPayload(request.identity(), meters, stream, start, end, days, adjusted));
        });
    }

    private Map<String, Object> dailyPayload(RequestIdentity identity, List<AuthorizedDevice> meters,
            StreamResult stream, LocalDate start, LocalDate end, int days, boolean adjusted) {
        Instant from = boundary(start);
        Instant to = boundary(end.plusDays(1));
        Map<String, List<Sample>> byMeter = stream.byMeter();
        Instant latest = stream.latest();
        List<Map<String, Object>> dayRows = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            LocalDate day = start.plusDays(index);
            Instant dayFrom = boundary(day);
            Instant dayTo = boundary(day.plusDays(1));
            int covered = 0;
            double sum = 0;
            for (AuthorizedDevice meter : meters) {
                PeriodFact period = comparisonPeriod(samples(byMeter, meter.id()), dayFrom, dayTo, !stream.truncated());
                if (period.usage() != null) {
                    covered++;
                    sum += scaled(meter, period.usage(), identity);
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("usage", covered == meters.size() ? round(sum) : null);
            row.put("coveredMeters", covered);
            row.put("expectedMeters", meters.size());
            row.put("complete", covered == meters.size() && !stream.truncated());
            row.put("elapsedUsage", null);
            row.put("elapsedMeters", 0);
            dayRows.add(row);
        }
        List<Map<String, Object>> meterRows = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            List<Sample> samples = samples(byMeter, meter.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", meter.id());
            row.put("name", meter.name());
            row.put("location", meter.installAddress());
            row.put("latest", latest == null ? null : format(latest));
            row.put("samples", samples.size());
            row.put("complete", !stream.truncated());
            Map<String, Object> daily = new LinkedHashMap<>();
            for (int index = 0; index < days; index++) {
                LocalDate day = start.plusDays(index);
                PeriodFact period = comparisonPeriod(samples, boundary(day), boundary(day.plusDays(1)),
                        !stream.truncated());
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("usage", period.usage() == null ? null : round(scaled(meter, period.usage(), identity)));
                cell.put("status", period.status());
                daily.put(day.toString(), cell);
            }
            row.put("daily", daily);
            meterRows.add(row);
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("currentDay", null);
        comparison.put("previousDay", null);
        comparison.put("lastWeekSameDay", null);
        if (latest != null) {
            Instant todayStart = boundary(dateOf(latest));
            long elapsed = latest.toEpochMilli() - todayStart.toEpochMilli();
            if (elapsed > 0) {
                Map<String, Object> current = elapsedSlice(identity, meters, byMeter, todayStart, elapsed, !stream.truncated());
                Map<String, Object> previous = elapsedSlice(identity, meters, byMeter,
                        todayStart.minusMillis(DAY_MS), elapsed, !stream.truncated());
                Map<String, Object> sameWeek = elapsedSlice(identity, meters, byMeter,
                        todayStart.minusMillis(7 * DAY_MS), elapsed, !stream.truncated());
                comparison.put("currentDay", current);
                comparison.put("previousDay", previous);
                comparison.put("lastWeekSameDay", sameWeek);
                for (Map<String, Object> day : dayRows) {
                    LocalDate value = LocalDate.parse(String.valueOf(day.get("date")));
                    Map<String, Object> slice = elapsedSlice(identity, meters, byMeter,
                            boundary(value), elapsed, !stream.truncated());
                    day.put("elapsedUsage", slice.get("usage"));
                    day.put("elapsedMeters", slice.get("coveredMeters"));
                }
            }
        }

        String latestComplete = null;
        for (int index = dayRows.size() - 1; index >= 0; index--) {
            if (Boolean.TRUE.equals(dayRows.get(index).get("complete"))) {
                latestComplete = String.valueOf(dayRows.get(index).get("date"));
                break;
            }
        }
        boolean policyActive = policyFor(identity, meters);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("timezone", "Asia/Shanghai");
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf");
        basis.put("boundaryHour", 8);
        basis.put("cycle", "08:00 → 次日 08:00（采样日）");
        basis.put("unit", "kWh");
        basis.put("nonOverlapping", true);
        basis.put("upstream", "ENERGY_HISTORY");
        payload.put("basis", basis);
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", format(from));
        window.put("to", format(to));
        window.put("requestedDays", days);
        window.put("endAdjustedToLatestCompleteDay", adjusted);
        payload.put("window", window);
        payload.put("latestSampleTime", latest == null ? null : format(latest));
        payload.put("latestCompleteDay", latestComplete);
        payload.put("truncated", stream.truncated());
        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("status", "verified");
        aggregation.put("label", policyActive ? "当前授权电表合计（已确认倍率）" : "当前授权电表合计");
        aggregation.put("note", policyActive ? "按已确认的不重叠集合与逐表倍率统计" : "已确认末端电表 · 度");
        aggregation.put("policyVersion", policyActive ? policy.version() : null);
        payload.put("aggregation", aggregation);
        payload.put("comparison", comparison);
        payload.put("days", dayRows);
        payload.put("meters", meterRows);
        Map<String, Object> dailyCoverage = new LinkedHashMap<>();
        dailyCoverage.put("meters", meters.size());
        dailyCoverage.put("truncated", stream.truncated());
        dailyCoverage.put("latestSampleAt", latest == null ? null : format(latest));
        payload.put("coverage", dailyCoverage);
        return payload;
    }

    private Map<String, Object> elapsedSlice(RequestIdentity identity, List<AuthorizedDevice> meters,
            Map<String, List<Sample>> byMeter, Instant start, long elapsed, boolean complete) {
        Instant end = start.plusMillis(elapsed);
        double usage = 0;
        int covered = 0;
        Map<String, Object> perMeter = new LinkedHashMap<>();
        for (AuthorizedDevice meter : meters) {
            PeriodFact period = comparisonPeriod(samples(byMeter, meter.id()), start, end, complete);
            if (period.status().equals("comparable") && period.usage() != null) {
                double value = scaled(meter, period.usage(), identity);
                covered++;
                perMeter.put(meter.id(), round(value));
                usage += value;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", start.atZone(SHANGHAI).toLocalDate().toString());
        result.put("from", format(start));
        result.put("to", format(end));
        result.put("usage", covered == meters.size() ? round(usage) : null);
        result.put("coveredMeters", covered);
        result.put("expectedMeters", meters.size());
        result.put("complete", covered == meters.size());
        result.put("meterUsage", perMeter);
        return result;
    }

    // ------------------------------------------------------------------
    // Weekly single-meter comparison (Runtime /energy-comparison contract).
    // ------------------------------------------------------------------

    private Mono<ObservationResponse> weeklyComparison(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        String id = required(p, "equipmentId");
        AuthorizedDevice meter = meters.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
        if (meter == null) return Mono.error(new BusinessException("FORBIDDEN", "该计量点不在当前公司观察范围内", 403));
        int count = integer(p.get("weeks"), 4, 2, 6);
        int boundaryHour = boundaryHour(p.get("boundaryHour"));
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate requestedEnd = p.containsKey("endDate") ? date(p.get("endDate")) : null;
        LocalDate endMonday = requestedEnd != null
                ? requestedEnd : today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (requestedEnd != null && requestedEnd.getDayOfWeek() != DayOfWeek.MONDAY) {
            return Mono.error(new ObservationUnavailableException("结束日须为已到达的周一"));
        }
        Instant end = endMonday.atTime(boundaryHour, 0).atZone(SHANGHAI).toInstant();
        if (end.isAfter(now)) {
            if (requestedEnd != null) return Mono.error(new ObservationUnavailableException("结束日须为已到达的周一"));
            end = end.minus(7, ChronoUnit.DAYS);
        }
        Instant start = end.minus((count + 1L) * 7L, ChronoUnit.DAYS);
        Instant stopBefore = start.minusMillis(LOOKBACK_MS);
        Instant endAt = now.isBefore(end.plus(7, ChronoUnit.DAYS)) ? now : end.plus(7, ChronoUnit.DAYS);
        Instant finalEnd = end;
        Set<String> meterIds = Set.of(meter.id());
        return readStream(request.identity(), meterIds, stopBefore, endAt).map(stream -> {
            List<Sample> samples = samples(stream.byMeter(), meter.id());
            boolean complete = !stream.truncated();
            List<PeriodFact> periods = new ArrayList<>();
            for (int index = 0; index <= count; index++) {
                Instant periodFrom = start.plus(index * 7L, ChronoUnit.DAYS);
                periods.add(comparisonPeriod(samples, periodFrom, periodFrom.plus(7, ChronoUnit.DAYS), complete));
            }
            PeriodFact baseline = periods.get(0);
            List<Map<String, Object>> weeks = new ArrayList<>();
            for (int index = 1; index < periods.size(); index++) {
                PeriodFact period = periods.get(index);
                Map<String, Object> week = periodMap(period);
                PeriodFact previous = periods.get(index - 1);
                if (!"comparable".equals(period.status()) || period.usage() == null) {
                    week.put("ratioNote", "缺少可比较的完整基期");
                } else if (!"comparable".equals(previous.status()) || previous.usage() == null) {
                    week.put("ratioNote", "缺少可比较的完整基期");
                } else if (previous.usage() == 0d) {
                    week.put("ratioNote", "上期零增量，环比不可计算");
                } else {
                    week.put("ratio", round((period.usage() - previous.usage()) / previous.usage() * 100));
                    week.put("ratioNote", "");
                }
                weeks.add(week);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reportId", "local-energy-comparison-" + Integer.toHexString(Objects.hash(
                    meter.id(), start, finalEnd, count, boundaryHour)));
            payload.put("type", "energy-comparison");
            payload.put("source", "company");
            payload.put("observedAt", clock.instant().toString());
            payload.put("status", weeklyStatus(weeks));
            payload.put("meter", Map.of("id", meter.id(), "name", meter.name(), "location", meter.installAddress()));
            Map<String, Object> basis = new LinkedHashMap<>();
            basis.put("field", "epf");
            basis.put("fieldLabel", "反向累计示数");
            basis.put("unit", null);
            basis.put("multiplier", null);
            basis.put("timezone", "Asia/Shanghai");
            basis.put("cycle", boundaryHour == 8 ? "周一08:00 → 下周一08:00（采样周）" : "周一00:00 → 下周一00:00（自然周）");
            basis.put("boundaryHour", boundaryHour);
            payload.put("basis", basis);
            Map<String, Object> window = new LinkedHashMap<>();
            window.put("from", format(start.plus(7, ChronoUnit.DAYS)));
            window.put("to", format(finalEnd));
            payload.put("window", window);
            payload.put("baseline", periodMap(baseline));
            payload.put("weeks", weeks);
            payload.put("summary", comparisonSummary(weeks));
            Map<String, Object> current = null;
            if (!finalEnd.isAfter(now) && now.isBefore(finalEnd.plus(7, ChronoUnit.DAYS))) {
                PeriodFact live = comparisonPeriod(samples, finalEnd, now, complete);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("requested", Map.of("from", format(finalEnd), "to", format(now)));
                value.put("partialUsage", live.usage());
                value.put("sampledThrough", live.last() == null ? null : format(live.last().readTime()));
                value.put("comparable", false);
                current = value;
            }
            payload.put("current", current);
            Map<String, Object> coverage = new LinkedHashMap<>();
            coverage.put("samples", samples.size());
            coverage.put("oldest", samples.isEmpty() ? null : format(samples.get(0).readTime()));
            coverage.put("latest", samples.isEmpty() ? null : format(samples.get(samples.size() - 1).readTime()));
            coverage.put("complete", complete);
            coverage.put("upstream", "ENERGY_HISTORY");
            coverage.put("truncated", stream.truncated());
            payload.put("coverage", coverage);
            payload.put("explanation", Map.of("status", "pending"));
            return energyResponse(payload);
        });
    }

    private static String weeklyStatus(List<Map<String, Object>> weeks) {
        long comparable = weeks.stream().filter(row -> "comparable".equals(row.get("status"))).count();
        if (comparable == weeks.size() && !weeks.isEmpty()) return "comparable";
        return comparable > 0 ? "partial" : "not_computable";
    }

    private static Map<String, Object> comparisonSummary(List<Map<String, Object>> weeks) {
        List<Map<String, Object>> valid = weeks.stream()
                .filter(row -> "comparable".equals(row.get("status")) && row.get("usage") instanceof Number)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comparableWeeks", valid.size());
        result.put("highest", valid.stream().max(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("week", row.get("key"), "usage", row.get("usage"))).orElse(null));
        result.put("lowest", valid.stream().min(Comparator.comparingDouble(row -> ((Number) row.get("usage")).doubleValue()))
                .map(row -> Map.of("week", row.get("key"), "usage", row.get("usage"))).orElse(null));
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

    // ------------------------------------------------------------------
    // Monthly company report.
    // ------------------------------------------------------------------

    private Mono<ObservationResponse> monthlyReport(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        int months = integer(p.get("months"), 3, 1, 12);
        int boundaryHour = boundaryHour(p.get("boundaryHour"));
        YearMonth latest = YearMonth.now(clock.withZone(SHANGHAI));
        YearMonth endMonth = p.containsKey("endMonth") ? yearMonth(p.get("endMonth")) : latest;
        if (endMonth.isAfter(latest)) return Mono.error(new ObservationUnavailableException("结束月份须为已到达月份"));
        List<MonthWindow> windows = new ArrayList<>();
        Instant now = clock.instant();
        for (int index = 0; index <= months; index++) {
            YearMonth month = endMonth.minusMonths(months - index);
            Instant from = month.atDay(1).atTime(boundaryHour, 0).atZone(SHANGHAI).toInstant();
            Instant next = month.plusMonths(1).atDay(1).atTime(boundaryHour, 0).atZone(SHANGHAI).toInstant();
            windows.add(new MonthWindow(month, from, next.isAfter(now) ? now : next, next.isAfter(now)));
        }
        Instant from = windows.get(0).from().minusMillis(LOOKBACK_MS);
        Instant to = windows.get(windows.size() - 1).to();
        return readStream(request.identity(), ids(meters), from, to).map(stream ->
                energyResponse(monthlyPayload(request.identity(), meters, stream, windows, boundaryHour)));
    }

    private Map<String, Object> monthlyPayload(RequestIdentity identity, List<AuthorizedDevice> meters,
            StreamResult stream, List<MonthWindow> windows, int boundaryHour) {
        List<Map<String, Object>> meterRows = new ArrayList<>();
        List<List<Map<String, Object>>> allPeriods = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            List<Sample> samples = samples(stream.byMeter(), meter.id());
            List<Map<String, Object>> periods = new ArrayList<>();
            for (MonthWindow window : windows) {
                PeriodFact fact = comparisonPeriod(samples, window.from(), window.to(), !stream.truncated());
                Map<String, Object> period = periodMap(fact);
                period.put("key", window.month().toString());
                period.put("usage", fact.usage() == null ? null : round(scaled(meter, fact.usage(), identity)));
                period.put("current", window.current());
                periods.add(period);
            }
            for (int index = 1; index < periods.size(); index++) {
                Map<String, Object> previous = periods.get(index - 1), current = periods.get(index);
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
            row.put("status", samples.isEmpty() && stream.truncated() ? "failed" : "ready");
            row.put("includedInTotal", false);
            row.put("unit", policyUnit(identity, meter));
            row.put("multiplier", policyMultiplier(identity, meter));
            row.put("periods", periods.subList(1, periods.size()));
            row.put("samples", samples.size());
            row.put("complete", !stream.truncated());
            row.put("latest", samples.isEmpty() ? null : format(samples.get(samples.size() - 1).readTime()));
            row.put("error", samples.isEmpty() && stream.truncated() ? "没有取得有效采样" : "");
            meterRows.add(row);
            allPeriods.add(periods.subList(1, periods.size()));
        }
        List<Map<String, Object>> periodRows = new ArrayList<>();
        for (int index = 0; index < windows.size() - 1; index++) {
            MonthWindow window = windows.get(index + 1);
            List<String> validIds = new ArrayList<>();
            double sum = 0;
            for (int meterIndex = 0; meterIndex < meters.size(); meterIndex++) {
                Map<String, Object> period = allPeriods.get(meterIndex).get(index);
                if ("comparable".equals(period.get("status")) && period.get("usage") instanceof Number value) {
                    validIds.add(meters.get(meterIndex).id());
                    sum += value.doubleValue();
                }
            }
            Map<String, Object> metric = null;
            if (!validIds.isEmpty()) {
                metric = new LinkedHashMap<>();
                metric.put("value", round(sum));
                metric.put("unit", "度");
                metric.put("status", validIds.size() == meters.size() ? "complete" : "partial");
                metric.put("meterIds", validIds);
            }
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("key", window.month().toString());
            period.put("from", format(window.from()));
            period.put("to", format(window.to()));
            period.put("current", window.current());
            period.put("metric", metric);
            period.put("coveredMeters", validIds.size());
            period.put("expectedMeters", meters.size());
            period.put("missingMeterIds", meters.stream().filter(m -> !validIds.contains(m.id())).map(AuthorizedDevice::id).toList());
            periodRows.add(period);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-company-energy-" + Integer.toHexString(Objects.hash(
                windows.get(0).from(), windows.get(windows.size() - 1).to())));
        payload.put("type", "company-energy");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("window", Map.of("from", format(windows.get(0).from()), "to", format(windows.get(windows.size() - 1).to())));
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("field", "epf");
        basis.put("timezone", "Asia/Shanghai");
        basis.put("boundaryHour", boundaryHour);
        basis.put("cycle", boundaryHour == 8 ? "月初08:00 → 下月初08:00（采样月）" : "自然月");
        basis.put("upstream", "ENERGY_HISTORY");
        payload.put("basis", basis);
        Map<String, Object> aggregation = new LinkedHashMap<>();
        boolean any = periodRows.stream().anyMatch(row -> row.get("metric") != null);
        aggregation.put("status", any ? "verified" : "not_computable");
        aggregation.put("label", "当前授权电表月报合计");
        aggregation.put("unit", any ? "度" : null);
        aggregation.put("policyVersion", policyFor(identity, meters) ? policy.version() : null);
        aggregation.put("note", any ? "按逐表08:00采样边界计算；覆盖不足的月份不补零" : "没有取得可比较的完整月度数据");
        payload.put("aggregation", aggregation);
        payload.put("baseline", null);
        payload.put("periods", periodRows);
        payload.put("meters", meterRows);
        payload.put("truncated", stream.truncated());
        long failed = meterRows.stream().filter(row -> "failed".equals(row.get("status"))).count();
        payload.put("progress", Map.of("status", failed == 0 ? "ready" : "partial", "total", meters.size(),
                "completed", meters.size() - failed, "failed", failed));
        payload.put("explanation", Map.of("status", "pending"));
        payload.put("coverage", Map.of("meters", meters.size(),
                "dataMeters", meterRows.stream().filter(row -> !((List<?>) row.get("periods")).isEmpty()).count(),
                "truncated", stream.truncated()));
        return payload;
    }

    // ------------------------------------------------------------------
    // Meter report: statistics, time comparison, region comparison, night.
    // ------------------------------------------------------------------

    private Mono<ObservationResponse> meterReport(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = request.parameters();
        String kind = p.getOrDefault("kind", "statistics").toLowerCase(Locale.ROOT);
        if (!List.of("statistics", "comparison").contains(kind)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "仅支持用电统计或对比", 400));
        }
        Instant from = strictInstant(p.get("from"));
        Instant to = strictInstant(p.get("to"));
        Instant now = clock.instant();
        if (!from.isBefore(to) || to.isAfter(now)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "结束须晚于开始且不晚于当前时刻", 400));
        }
        if (p.containsKey("otherFrom") || p.containsKey("otherTo")) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW",
                    "一次只能改变一个参数：同时改变时间与范围请先澄清要对比什么", 400));
        }
        boolean hasDailyWindow = p.containsKey("windowFrom") || p.containsKey("windowTo");
        if (hasDailyWindow && (!"20:00".equals(p.get("windowFrom")) || !"08:00".equals(p.get("windowTo")))) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW",
                    "每日时段统计目前仅支持20:00至次日08:00", 400));
        }
        Scope scope;
        try {
            scope = resolveScope(p, meters, "");
        } catch (BusinessException error) {
            return Mono.error(error);
        }
        String axis = null;
        Scope other = null;
        Instant baseFrom = null, baseTo = null;
        if ("comparison".equals(kind)) {
            axis = p.getOrDefault("axis", "time");
            if (!List.of("time", "region").contains(axis)) {
                return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "对比轴仅支持 time 或 region", 400));
            }
            boolean hasBase = p.containsKey("baseFrom") || p.containsKey("baseTo");
            boolean hasOther = hasOtherScope(p);
            if (hasBase && hasOther) {
                return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "时间对比与区域对比不能同时进行：请先澄清要对比什么", 400));
            }
            if ("time".equals(axis)) {
                if (hasOther) return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "时间对比固定计量范围，不接受另一个范围", 400));
                baseFrom = p.containsKey("baseFrom") ? strictInstant(p.get("baseFrom")) : from.minusMillis(to.toEpochMilli() - from.toEpochMilli());
                baseTo = p.containsKey("baseTo") ? strictInstant(p.get("baseTo")) : from;
                if (!baseFrom.isBefore(baseTo) || baseTo.isAfter(now)) {
                    return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "基期结束须晚于开始且不晚于当前时刻", 400));
                }
            } else {
                if (hasBase) return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "区域对比固定时间跨度，不接受基期", 400));
                if (!hasOther) return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "区域对比需要另一个计量范围（otherScopeLevel）", 400));
                try {
                    other = resolveScope(p, meters, "other");
                } catch (BusinessException error) {
                    return Mono.error(error);
                }
            }
        } else if (p.containsKey("baseFrom") || p.containsKey("baseTo") || hasOtherScope(p)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "统计报告不接受基期或对照范围", 400));
        }
        if (hasDailyWindow && "comparison".equals(kind) && !"time".equals(axis)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW",
                    "夜间对比仅支持同一范围的时间对比", 400));
        }
        String grain = p.get("grain");
        if (grain != null && !GRAINS.contains(grain)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "分段粒度仅支持时/日/周/月", 400));
        }
        Instant stopBefore = min(from, baseFrom == null ? from : baseFrom).minusMillis(LOOKBACK_MS);
        Instant endAt = max(to, baseTo == null ? to : baseTo).plusMillis(LOOKAHEAD_MS);
        Scope finalScope = scope;
        Scope finalOther = other;
        Instant finalBaseFrom = baseFrom, finalBaseTo = baseTo;
        String finalAxis = axis;
        return readStream(request.identity(), ids(meters), stopBefore, endAt).map(stream -> {
            Map<String, Object> payload;
            if (hasDailyWindow && "comparison".equals(kind)) {
                payload = nightTimeComparison(request.identity(), finalScope, meters, stream,
                        from, to, finalBaseFrom, finalBaseTo);
            } else if (hasDailyWindow) {
                payload = nightStatistics(request.identity(), finalScope, meters, stream, from, to);
            } else if ("comparison".equals(kind) && "region".equals(finalAxis)) {
                payload = regionComparison(request.identity(), finalScope, finalOther, meters, stream, from, to);
            } else if ("comparison".equals(kind)) {
                payload = timeComparison(request.identity(), request, finalScope, meters, stream,
                        from, to, finalBaseFrom, finalBaseTo);
            } else {
                payload = statistics(request.identity(), request, finalScope, meters, stream, from, to, grain);
            }
            return energyResponse(payload);
        });
    }

    private Map<String, Object> statistics(RequestIdentity identity, ObservationRequest request, Scope scope,
            List<AuthorizedDevice> all, StreamResult stream, Instant from, Instant to, String requestedGrain) {
        Map<String, List<Sample>> byMeter = stream.byMeter();
        Instant effTo = effectiveEndOf(all, byMeter, from, to);
        long effSpan = Math.max(HOUR_MS, effTo.toEpochMilli() - from.toEpochMilli());
        double effDays = effSpan / (double) DAY_MS;
        List<StatisticsRow> catalogRows = new ArrayList<>();
        for (AuthorizedDevice device : all) catalogRows.add(statisticsRow(identity, device, byMeter, from, to, effTo));
        List<StatisticsRow> rows = catalogRows.stream().filter(row -> scope.ids().contains(row.id())).toList();
        List<StatisticsRow> valid = rows.stream().filter(row -> "ok".equals(row.status())).toList();
        double total = round(valid.stream().mapToDouble(StatisticsRow::value).sum());
        int dataMeters = valid.size();

        double[] hourEnergy = new double[24];
        double[] hourCovered = new double[24];
        Map<Long, Double> slotEnergy = new HashMap<>();
        Map<Long, Double> slotCovered = new HashMap<>();
        long coveredMs = 0;
        for (StatisticsRow row : valid) {
            coveredMs += hourlyAllocation(samples(byMeter, row.id()), from.toEpochMilli(), to.toEpochMilli(),
                    hourEnergy, hourCovered, slotEnergy, slotCovered);
        }
        double coveredFraction = dataMeters == 0 ? 0 : coveredMs / (double) (dataMeters * effSpan);
        boolean sufficient = coveredFraction >= 0.5;
        List<Object> rates = null;
        if (sufficient) {
            rates = new ArrayList<>();
            for (int hour = 0; hour < 24; hour++) {
                rates.add(hourCovered[hour] > 0 ? round(hourEnergy[hour] / (hourCovered[hour] / (double) HOUR_MS) * dataMeters) : null);
            }
        }
        Double nightBase = nightAverage(valid, byMeter, from, to);
        List<DayEnergy> days = meteringDayEnergy(valid, byMeter, from, effTo);
        DayEnergy peakDay = days.stream().max(Comparator.comparingDouble(DayEnergy::value)).orElse(null);
        Map<String, Object> peakHour = null;
        if (sufficient && !slotEnergy.isEmpty()) {
            Map.Entry<Long, Double> top = slotEnergy.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) {
                peakHour = new LinkedHashMap<>();
                peakHour.put("value", round(top.getValue()));
                peakHour.put("sub", String.format("%02d:00", Instant.ofEpochMilli(top.getKey()).atZone(SHANGHAI).getHour()));
            }
        }
        String grain = requestedGrain != null ? requestedGrain : defaultGrain(to.toEpochMilli() - from.toEpochMilli());
        Map<String, List<Map<String, Object>>> bucketsAll = new LinkedHashMap<>();
        bucketsAll.put("hour", hourBuckets(from, to, effTo, valid, byMeter, slotEnergy, slotCovered, dataMeters));
        for (String each : List.of("day", "week", "month")) {
            bucketsAll.put(each, boundaryBuckets(from, to, effTo, each, valid, byMeter));
        }
        List<Map<String, Object>> locations = groupLocations(valid);
        Map<String, Object> excluded = excluded(rows);
        Map<String, Object> context;
        Map<String, Object> peer = null;
        List<Map<String, Object>> compositionSource = locations;
        Map<String, Object> compositionExcluded = excluded;
        String highlight = null;
        if ("all".equals(scope.level())) {
            context = floorSplit(catalogRows);
        } else if ("location".equals(scope.level())) {
            StatisticsRow target = rows.get(0);
            List<StatisticsRow> floorRows = catalogRows.stream().filter(row -> Objects.equals(row.floor(), target.floor())).toList();
            List<StatisticsRow> floorOk = floorRows.stream().filter(row -> "ok".equals(row.status())).toList();
            double floorTotal = floorOk.stream().mapToDouble(StatisticsRow::value).sum();
            List<Map<String, Object>> floorLocations = groupLocations(floorOk);
            highlight = target.areaName().isBlank() ? target.name() : target.areaName();
            int rank = 0;
            for (int index = 0; index < floorLocations.size(); index++) {
                if (highlight.equals(floorLocations.get(index).get("label"))) { rank = index + 1; break; }
            }
            Map<String, Object> contextValue = new LinkedHashMap<>();
            contextValue.put("kind", "peer");
            contextValue.put("parentLabel", target.floor() == null ? "本层" : target.floor());
            contextValue.put("share", floorTotal > 0 ? round(target.value() / floorTotal * 100) : null);
            contextValue.put("rank", rank == 0 ? null : rank);
            contextValue.put("of", floorLocations.size());
            context = contextValue;
            peer = peerCurve(identity, floorOk, byMeter, from, to, effSpan);
            compositionSource = floorLocations;
            compositionExcluded = excluded(floorRows);
        } else {
            List<StatisticsRow> parentOk = catalogRows.stream().filter(row -> "ok".equals(row.status())).toList();
            double parentTotal = parentOk.stream().mapToDouble(StatisticsRow::value).sum();
            Map<String, Object> contextValue = new LinkedHashMap<>();
            contextValue.put("kind", "parent");
            contextValue.put("parentLabel", "全部电表");
            contextValue.put("share", parentTotal > 0 ? round(total / parentTotal * 100) : null);
            contextValue.put("perMeterDaily", dataMeters > 0 ? round(total / (dataMeters * effDays)) : null);
            contextValue.put("parentPerMeterDaily", parentOk.isEmpty() ? null : round(parentTotal / (parentOk.size() * effDays)));
            context = contextValue;
        }
        List<Map<String, Object>> compositionRows = new ArrayList<>(compositionSource.subList(0, Math.min(8, compositionSource.size())));
        String visibleHighlight = highlight;
        if (visibleHighlight != null && compositionRows.stream().noneMatch(row -> visibleHighlight.equals(row.get("label")))) {
            compositionSource.stream().filter(row -> visibleHighlight.equals(row.get("label"))).findFirst()
                    .ifPresent(row -> compositionRows.add(new LinkedHashMap<>(row)));
        }
        Map<String, Object> rest = null;
        if (compositionSource.size() > compositionRows.size()) {
            int count = compositionSource.size() - compositionRows.size();
            double value = compositionSource.subList(8, compositionSource.size()).stream()
                    .filter(row -> compositionRows.stream().noneMatch(kept -> kept.get("label").equals(row.get("label"))))
                    .mapToDouble(row -> ((Number) row.get("value")).doubleValue()).sum();
            rest = Map.of("count", count, "value", round(value));
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        if (to.toEpochMilli() - from.toEpochMilli() <= DAY_MS) {
            metrics.add(metric("dayTotal", dataMeters > 0 ? total : null, "度"));
        } else {
            metrics.add(metric("dailyAvg", dataMeters > 0 ? round(total / effDays) : null, "度/日"));
        }
        metrics.add(metric("nightBase", nightBase, "度/时"));
        if (to.toEpochMilli() - from.toEpochMilli() <= DAY_MS) {
            metrics.add(metric("peakHour", peakHour == null ? null : (Double) peakHour.get("value"), "度"));
        } else {
            metrics.add(metric("peakDay", peakDay == null ? null : round(peakDay.value()), "度"));
        }
        double fourth;
        if ("location".equals(scope.level())) {
            double nightUsage = 0, dayUsage = 0;
            if (!valid.isEmpty()) {
                for (DayEnergy day : days) {
                    Instant end = boundary(LocalDate.parse(day.day())).plusMillis(DAY_MS);
                    Double night = completeWindowEnergy(samples(byMeter, valid.get(0).id()), end.minusMillis(12 * HOUR_MS), end);
                    if (night != null) { nightUsage += night; dayUsage += day.value(); }
                }
            }
            fourth = dayUsage > 0 ? round(nightUsage / dayUsage * 100) : 0;
            Map<String, Object> share = metric("nightShare", dayUsage > 0 ? fourth : null, "%");
            share.put("nightWindow", "20:00-08:00");
            share.put("dayWindow", "previous08:00-08:00");
            metrics.add(share);
        } else {
            double top3 = locations.stream().limit(3).mapToDouble(row -> ((Number) row.get("value")).doubleValue()).sum();
            metrics.add(metric("top3Share", total > 0 ? round(top3 / total * 100) : null, "%"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-meter-statistics-" + Integer.toHexString(Objects.hash(
                identity.ownerKey(), scope.level(), from, to)));
        payload.put("type", "meter-report");
        payload.put("kind", "statistics");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        span.put("observed", observed(valid));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("span", span);
        params.put("scope", scopeMap(scope));
        params.put("grain", grain);
        payload.put("params", params);
        payload.put("total", totalView(dataMeters > 0 ? total : null));
        payload.put("buckets", Map.of("grain", grain, "items", bucketsAll.get(grain)));
        payload.put("bucketsAll", bucketsAll);
        payload.put("context", context);
        if (peer != null) payload.put("peer", peer);
        Map<String, Object> shape24 = new LinkedHashMap<>();
        shape24.put("rates", rates);
        shape24.put("coveredFraction", round(coveredFraction));
        payload.put("shape24", shape24);
        payload.put("metrics", metrics);
        Map<String, Object> composition = new LinkedHashMap<>();
        composition.put("kind", "ranking");
        composition.put("rows", compositionRows);
        composition.put("rest", rest);
        composition.put("excluded", compositionExcluded);
        payload.put("composition", composition);
        payload.put("coverage", coverage(rows, stream.truncated(), stream.latest()));
        payload.put("meters", rows.stream().map(CompanyEnergyObservationComposerImpl::statisticsRowMap).toList());
        payload.put("authorizationMeterIds", all.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    // ------------------------------------------------------------------
    // Night statistics (20:00-08:00 only, complete windows only).
    // ------------------------------------------------------------------

    private Map<String, Object> nightStatistics(RequestIdentity identity, Scope scope, List<AuthorizedDevice> all,
            StreamResult stream, Instant from, Instant to) {
        Map<String, List<Sample>> byMeter = stream.byMeter();
        List<NightWindow> windows = new ArrayList<>();
        long start = ceilDiv(from.toEpochMilli() - 12 * HOUR_MS, DAY_MS) * DAY_MS + 12 * HOUR_MS;
        for (; start + 12 * HOUR_MS <= to.toEpochMilli(); start += DAY_MS) {
            windows.add(new NightWindow(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 12 * HOUR_MS)));
        }
        List<NightMeter> rows = new ArrayList<>();
        for (AuthorizedDevice meter : all) {
            if (!scope.ids().contains(meter.id())) continue;
            List<Sample> samples = samples(byMeter, meter.id());
            List<Map<String, Object>> cells = new ArrayList<>();
            double shareUsage = 0, dayUsage = 0;
            int valid = 0, shared = 0;
            for (NightWindow window : windows) {
                Double value = completeWindowEnergy(samples, window.from(), window.to());
                Double dayValue = window.to().toEpochMilli() - DAY_MS >= from.toEpochMilli()
                        ? completeWindowEnergy(samples, window.to().minusMillis(DAY_MS), window.to()) : null;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("from", format(window.from()));
                cell.put("to", format(window.to()));
                cell.put("value", value == null ? null : round(scaled(meter, value, identity)));
                cell.put("dayValue", dayValue == null ? null : round(scaled(meter, dayValue, identity)));
                cells.add(cell);
                if (value != null) valid++;
                if (value != null && dayValue != null) {
                    shared++;
                    shareUsage += scaled(meter, value, identity);
                    dayUsage += scaled(meter, dayValue, identity);
                }
            }
            Double usage = valid == 0 ? null : round(cells.stream()
                    .map(cell -> cell.get("value"))
                    .filter(Number.class::isInstance).mapToDouble(value -> ((Number) value).doubleValue()).sum());
            boolean inSpan = samples.stream().anyMatch(sample ->
                    !sample.readTime().isBefore(from) && !sample.readTime().isAfter(to));
            String status = valid == windows.size() && valid > 0 ? "ok"
                    : valid > 0 || inSpan ? "partial" : "noData";
            Map<String, Object> night = new LinkedHashMap<>();
            night.put("usage", usage);
            night.put("validDays", valid);
            night.put("expectedDays", windows.size());
            night.put("shareUsage", round(shareUsage));
            night.put("dayUsage", round(dayUsage));
            night.put("shareDays", shared);
            night.put("share", dayUsage > 0 ? round(shareUsage / dayUsage * 100) : null);
            night.put("windows", cells);
            StatisticsRow base = new StatisticsRow(meter.id(), meter.name(), meter.installAddress(),
                    floorOf(meter.installAddress()), usage == null ? 0 : usage, status, null, null);
            rows.add(new NightMeter(base, usage, status, night, cells));
        }
        double total = round(rows.stream().filter(row -> row.usage() != null)
                .mapToDouble(row -> row.usage()).sum());
        List<Map<String, Object>> ranked = groupNightLocations(rows);
        List<Map<String, Object>> dayBuckets = new ArrayList<>();
        for (int index = 0; index < windows.size(); index++) {
            double sum = 0;
            int valid = 0;
            for (NightMeter row : rows) {
                if (row.cells().get(index).get("value") instanceof Number value) {
                    sum += value.doubleValue();
                    valid++;
                }
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(windows.get(index).from()));
            bucket.put("to", format(windows.get(index).to()));
            bucket.put("value", valid == 0 ? null : round(sum));
            bucket.put("partial", valid < rows.size());
            bucket.put("days", 1);
            dayBuckets.add(bucket);
        }
        Map<String, List<Map<String, Object>>> bucketsAll = new LinkedHashMap<>();
        bucketsAll.put("hour", List.of());
        bucketsAll.put("day", dayBuckets);
        for (String grain : List.of("week", "month")) {
            List<Map<String, Object>> aggregate = new ArrayList<>();
            for (ReportWindow window : bucketWindows(from, to, grain)) {
                List<Map<String, Object>> parts = dayBuckets.stream()
                        .filter(bucket -> !Instant.parse(String.valueOf(bucket.get("from"))).isBefore(window.from())
                                && Instant.parse(String.valueOf(bucket.get("from"))).isBefore(window.to()))
                        .toList();
                double sum = parts.stream().filter(bucket -> bucket.get("value") instanceof Number)
                        .mapToDouble(bucket -> ((Number) bucket.get("value")).doubleValue()).sum();
                boolean partial = !window.full() || parts.size() == 0
                        || parts.stream().anyMatch(bucket -> Boolean.TRUE.equals(bucket.get("partial")));
                Map<String, Object> bucket = new LinkedHashMap<>();
                bucket.put("from", format(window.from()));
                bucket.put("to", format(window.to()));
                bucket.put("value", sum == 0 && parts.stream().noneMatch(b -> b.get("value") != null) ? null : round(sum));
                bucket.put("partial", partial);
                bucket.put("days", parts.size());
                aggregate.add(bucket);
            }
            bucketsAll.put(grain, aggregate);
        }
        String grain = to.toEpochMilli() - from.toEpochMilli() <= 14 * DAY_MS ? "day"
                : to.toEpochMilli() - from.toEpochMilli() <= 91 * DAY_MS ? "week" : "month";
        List<Map<String, Object>> full = dayBuckets.stream()
                .filter(bucket -> !Boolean.TRUE.equals(bucket.get("partial")) && bucket.get("value") != null).toList();
        Map<String, Object> peak = full.stream().max(Comparator.comparingDouble(bucket -> ((Number) bucket.get("value")).doubleValue()))
                .orElse(null);
        double shareUsage = rows.stream().mapToDouble(row -> ((Number) row.night().get("shareUsage")).doubleValue()).sum();
        double dayUsage = rows.stream().mapToDouble(row -> ((Number) row.night().get("dayUsage")).doubleValue()).sum();
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("dailyAvg", !full.isEmpty() && full.size() == windows.size() ? round(total / full.size()) : null, "度/夜"));
        metrics.add(metric("nightBase", !full.isEmpty()
                ? round(full.stream().mapToDouble(bucket -> ((Number) bucket.get("value")).doubleValue()).sum() / full.size() / 12) : null,
                "度/时"));
        Map<String, Object> peakMetric = metric("peakDay", peak == null ? null : (Double) peak.get("value"), "度");
        if (peak != null) peakMetric.put("sub", String.valueOf(peak.get("to")).substring(0, 10));
        metrics.add(peakMetric);
        Map<String, Object> shareMetric = metric("nightShare", dayUsage > 0 ? round(shareUsage / dayUsage * 100) : null, "%");
        shareMetric.put("nightWindow", "20:00-08:00");
        shareMetric.put("dayWindow", "previous08:00-08:00");
        metrics.add(shareMetric);
        List<Map<String, Object>> scopeRows = rows.stream().map(row -> {
            Map<String, Object> out = statisticsRowMap(row.row());
            out.put("night", row.night());
            return out;
        }).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-meter-night-" + Integer.toHexString(Objects.hash(
                identity.ownerKey(), from, to)));
        payload.put("type", "meter-report");
        payload.put("kind", "statistics");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("from", dayBuckets.stream().filter(b -> b.get("value") != null).findFirst()
                .map(b -> b.get("from")).orElse(null));
        observed.put("to", dayBuckets.stream().filter(b -> b.get("value") != null).reduce((a, b) -> b)
                .map(b -> b.get("to")).orElse(null));
        span.put("observed", observed);
        params.put("span", span);
        params.put("scope", scopeMap(scope));
        params.put("grain", grain);
        Map<String, Object> dailyWindow = new LinkedHashMap<>();
        dailyWindow.put("from", "20:00");
        dailyWindow.put("to", "08:00");
        dailyWindow.put("timezone", "Asia/Shanghai");
        params.put("dailyWindow", dailyWindow);
        payload.put("params", params);
        payload.put("total", totalView(rows.stream().anyMatch(row -> row.usage() != null) ? total : null));
        payload.put("buckets", Map.of("grain", grain, "items", bucketsAll.get(grain)));
        payload.put("bucketsAll", bucketsAll);
        payload.put("context", floorSplit(rows.stream().map(NightMeter::row).toList()));
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("rates", null);
        shape.put("coveredFraction", 0);
        payload.put("shape24", shape);
        payload.put("metrics", metrics);
        Map<String, Object> windowSummary = new LinkedHashMap<>();
        windowSummary.put("expectedNights", windows.size());
        windowSummary.put("validMeterNights", rows.stream().mapToInt(row -> ((Number) row.night().get("validDays")).intValue()).sum());
        windowSummary.put("expectedMeterNights", rows.size() * windows.size());
        windowSummary.put("pairedMeterDays", rows.stream().mapToInt(row -> ((Number) row.night().get("shareDays")).intValue()).sum());
        windowSummary.put("pairedNightUsage", round(shareUsage));
        windowSummary.put("pairedDayUsage", round(dayUsage));
        payload.put("windowSummary", windowSummary);
        Map<String, Object> composition = new LinkedHashMap<>();
        composition.put("kind", "ranking");
        composition.put("rows", ranked);
        composition.put("rest", null);
        List<String> noData = rows.stream().filter(row -> "noData".equals(row.status())).map(row -> row.row().id()).toList();
        List<Map<String, Object>> partial = rows.stream().filter(row -> "partial".equals(row.status()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.row().id());
                    value.put("through", row.night().get("windows") instanceof List<?> cells && !cells.isEmpty()
                            ? ((Map<?, ?>) cells.get(cells.size() - 1)).get("to") : null);
                    return value;
                }).toList();
        Map<String, Object> excludedMap = new LinkedHashMap<>();
        excludedMap.put("noData", noData.size());
        excludedMap.put("partial", partial.size());
        excludedMap.put("through", null);
        composition.put("excluded", excludedMap);
        payload.put("composition", composition);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("meters", rows.size());
        coverage.put("dataMeters", rows.stream().filter(row -> row.usage() != null).count());
        coverage.put("noData", noData);
        coverage.put("partial", partial);
        coverage.put("truncated", stream.truncated());
        coverage.put("latestSampleAt", stream.latest() == null ? null : format(stream.latest()));
        payload.put("coverage", coverage);
        payload.put("meters", scopeRows);
        payload.put("authorizationMeterIds", all.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    private static List<NightWindow> nightWindows(Instant from, Instant to) {
        List<NightWindow> windows = new ArrayList<>();
        long start = ceilDiv(from.toEpochMilli() - 12 * HOUR_MS, DAY_MS) * DAY_MS + 12 * HOUR_MS;
        for (; start + 12 * HOUR_MS <= to.toEpochMilli(); start += DAY_MS) {
            windows.add(new NightWindow(Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 12 * HOUR_MS)));
        }
        return windows;
    }

    private Map<String, Object> nightTimeComparison(RequestIdentity identity, Scope scope,
            List<AuthorizedDevice> all, StreamResult stream, Instant from, Instant to,
            Instant baseFrom, Instant baseTo) {
        Map<String, List<Sample>> byMeter = stream.byMeter();
        List<NightWindow> windowsA = nightWindows(from, to);
        List<NightWindow> windowsB = nightWindows(baseFrom, baseTo);
        List<ComparisonRow> combined = new ArrayList<>();
        for (AuthorizedDevice device : all) {
            combined.add(new ComparisonRow(device.id(), device.name(), device.installAddress(),
                    floorOf(device.installAddress()),
                    nightSideStatus(identity, device, byMeter, windowsA),
                    nightSideStatus(identity, device, byMeter, windowsB)));
        }
        List<ComparisonRow> scopeRows = combined.stream().filter(row -> scope.ids().contains(row.id())).toList();
        List<ComparisonRow> cohort = scopeRows.stream()
                .filter(row -> "ok".equals(row.a().status()) && "ok".equals(row.b().status())).toList();
        List<ComparisonRow> aOk = cohort.isEmpty()
                ? scopeRows.stream().filter(row -> "ok".equals(row.a().status())).toList() : cohort;
        List<ComparisonRow> bOk = cohort.isEmpty()
                ? scopeRows.stream().filter(row -> "ok".equals(row.b().status())).toList() : cohort;
        double totalA = round(aOk.stream().mapToDouble(row -> row.a().value()).sum());
        double totalB = round(bOk.stream().mapToDouble(row -> row.b().value()).sum());
        Double avgA = windowsA.isEmpty() ? null : round(totalA / windowsA.size());
        Double avgB = windowsB.isEmpty() ? null : round(totalB / windowsB.size());
        boolean unequal = windowsA.size() != windowsB.size();
        Map<String, Object> diff = new LinkedHashMap<>();
        if (cohort.isEmpty()) {
            diff.put("value", null);
            diff.put("pct", null);
            diff.put("pctNote", aOk.isEmpty() && !bOk.isEmpty() ? "本期无有效采样"
                    : !aOk.isEmpty() && bOk.isEmpty() ? "基期无有效采样" : "数据不足");
        } else if (unequal) {
            double value = round(((Number) avgA).doubleValue() - ((Number) avgB).doubleValue());
            diff.put("value", value);
            diff.put("pct", ((Number) avgB).doubleValue() > 0
                    ? round(value / ((Number) avgB).doubleValue() * 100) : null);
            if (((Number) avgB).doubleValue() <= 0) diff.put("pctNote", "基期为零");
            diff.put("basis", "nightlyAvg");
            diff.put("unit", "度/夜");
        } else {
            double value = round(totalA - totalB);
            diff.put("value", value);
            diff.put("pct", totalB > 0 ? round(value / totalB * 100) : null);
            if (totalB <= 0) diff.put("pctNote", "基期为零");
            diff.put("basis", "total");
            diff.put("unit", "度");
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metricComparison("nightTotal", "度", totalA, totalB));
        metrics.add(metricComparison("nightlyAvg", "度/夜",
                avgA == null ? null : avgA.doubleValue(), avgB == null ? null : avgB.doubleValue()));
        Map<String, Object> contributionView = contribution(scope, combined, cohort, unequal,
                Math.max(1, windowsA.size()), Math.max(1, windowsB.size()));
        contributionView.put("unit", unequal ? "度/夜" : "度");
        Aggregate sideA = new Aggregate(totalA, aOk.size(), 0, null,
                avgA == null ? null : avgA.doubleValue(), totalA, null, null,
                null, null, List.of(), List.of());
        Map<String, Object> context = comparisonContext(scope, scopeRows, combined, cohort, sideA,
                Math.max(1, windowsA.size()));
        List<String> noData = scopeRows.stream()
                .filter(row -> "noData".equals(row.a().status()) || "noData".equals(row.b().status()))
                .map(ComparisonRow::id).toList();
        List<Map<String, Object>> partial = scopeRows.stream()
                .filter(row -> !cohort.contains(row) && !noData.contains(row.id()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.id());
                    value.put("through", windowsA.isEmpty() ? null : format(windowsA.get(windowsA.size() - 1).to()));
                    return value;
                }).toList();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("meters", scopeRows.size());
        coverage.put("dataMeters", cohort.size());
        coverage.put("noData", noData);
        coverage.put("partial", partial);
        coverage.put("addedMeterIds", scopeRows.stream()
                .filter(row -> "ok".equals(row.a().status()) && "noData".equals(row.b().status()))
                .map(ComparisonRow::id).toList());
        coverage.put("removedMeterIds", scopeRows.stream()
                .filter(row -> "ok".equals(row.b().status()) && "noData".equals(row.a().status()))
                .map(ComparisonRow::id).toList());
        coverage.put("truncated", stream.truncated());
        coverage.put("expectedNights", Map.of("current", windowsA.size(), "base", windowsB.size()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("axis", "time");
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        span.put("observed", observed(scopeRows.stream().map(ComparisonRow::a).toList()));
        params.put("span", span);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("requested", Map.of("from", format(baseFrom), "to", format(baseTo)));
        baseline.put("observed", observed(scopeRows.stream().map(ComparisonRow::b).toList()));
        params.put("baseline", baseline);
        params.put("scope", scopeMap(scope));
        params.put("dailyWindow", Map.of("from", "20:00", "to", "08:00", "timezone", "Asia/Shanghai"));
        Map<String, Object> sideAView = new LinkedHashMap<>();
        sideAView.put("total", totalView(totalA));
        sideAView.put("dataMeters", aOk.size());
        sideAView.put("effectiveNights", windowsA.size());
        sideAView.put("nightlyAvg", avgA);
        Map<String, Object> sideBView = new LinkedHashMap<>();
        sideBView.put("total", totalView(totalB));
        sideBView.put("dataMeters", bOk.size());
        sideBView.put("effectiveNights", windowsB.size());
        sideBView.put("nightlyAvg", avgB);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-meter-night-comparison-" + Integer.toHexString(Objects.hash(
                identity.ownerKey(), scope.level(), from, to, baseFrom, baseTo)));
        payload.put("type", "meter-report");
        payload.put("kind", "comparison");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("params", params);
        payload.put("sides", Map.of("a", sideAView, "b", sideBView));
        payload.put("diff", diff);
        payload.put("metrics", metrics);
        payload.put("contribution", contributionView);
        payload.put("context", context);
        payload.put("coverage", coverage);
        payload.put("meters", scopeRows.stream().map(CompanyEnergyObservationComposerImpl::comparisonRowMap).toList());
        payload.put("authorizationMeterIds", all.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    private StatisticsRow nightSideStatus(RequestIdentity identity, AuthorizedDevice device,
            Map<String, List<Sample>> byMeter, List<NightWindow> windows) {
        List<Sample> samples = samples(byMeter, device.id());
        boolean inSpan = false;
        double sum = 0;
        int valid = 0;
        for (NightWindow window : windows) {
            Double value = completeWindowEnergy(samples, window.from(), window.to());
            if (value != null) {
                valid++;
                sum += scaled(device, value, identity);
            }
        }
        for (Sample sample : samples) {
            for (NightWindow window : windows) {
                if (!sample.readTime().isBefore(window.from()) && sample.readTime().isBefore(window.to())) {
                    inSpan = true;
                    break;
                }
            }
        }
        String status = valid == windows.size() && !windows.isEmpty() ? "ok"
                : valid > 0 || inSpan ? "partial" : "noData";
        return new StatisticsRow(device.id(), device.name(), device.installAddress(),
                floorOf(device.installAddress()), status.equals("ok") ? round(sum) : 0,
                status, null, null);
    }

    // ------------------------------------------------------------------
    // Time comparison.
    // ------------------------------------------------------------------

    private Map<String, Object> timeComparison(RequestIdentity identity, ObservationRequest request, Scope scope,
            List<AuthorizedDevice> all, StreamResult stream, Instant from, Instant to,
            Instant baseFrom, Instant baseTo) {
        Map<String, List<Sample>> byMeter = stream.byMeter();
        Instant effToA = effectiveEndOf(all, byMeter, from, to);
        Instant effToB = effectiveEndOf(all, byMeter, baseFrom, baseTo);
        List<ComparisonRow> combined = new ArrayList<>();
        for (AuthorizedDevice device : all) {
            ComparisonRow row = new ComparisonRow(device.id(), device.name(), device.installAddress(),
                    floorOf(device.installAddress()),
                    sideStatus(identity, device, byMeter, from, to, effToA),
                    sideStatus(identity, device, byMeter, baseFrom, baseTo, effToB));
            combined.add(row);
        }
        List<ComparisonRow> scopeRows = combined.stream().filter(row -> scope.ids().contains(row.id())).toList();
        List<ComparisonRow> cohort = scopeRows.stream().filter(row -> "ok".equals(row.a().status()) && "ok".equals(row.b().status())).toList();
        List<ComparisonRow> aOk = cohort.isEmpty()
                ? scopeRows.stream().filter(row -> "ok".equals(row.a().status())).toList() : cohort;
        List<ComparisonRow> bOk = cohort.isEmpty()
                ? scopeRows.stream().filter(row -> "ok".equals(row.b().status())).toList() : cohort;
        Aggregate sideA = sideAggregate(aRows(aOk), byMeter, from, to, effToA);
        Aggregate sideB = sideAggregate(bRows(bOk), byMeter, baseFrom, baseTo, effToB);
        boolean isDay = to.toEpochMilli() - from.toEpochMilli() <= DAY_MS
                && baseTo.toEpochMilli() - baseFrom.toEpochMilli() <= DAY_MS;
        double effDaysA = Math.max(HOUR_MS, effToA.toEpochMilli() - from.toEpochMilli()) / (double) DAY_MS;
        double effDaysB = Math.max(HOUR_MS, effToB.toEpochMilli() - baseFrom.toEpochMilli()) / (double) DAY_MS;
        boolean unequalSpan = !isDay && Math.abs(effDaysA - effDaysB) > 1e-9;
        Map<String, Object> diff = new LinkedHashMap<>();
        if (cohort.isEmpty()) {
            diff.put("value", null);
            diff.put("pct", null);
            diff.put("pctNote", aOk.isEmpty() && !bOk.isEmpty() ? "本期无有效采样"
                    : !aOk.isEmpty() && bOk.isEmpty() ? "基期无有效采样" : "数据不足");
        } else if (unequalSpan) {
            double value = round(sideA.dailyAvg() - sideB.dailyAvg());
            diff.put("value", value);
            diff.put("pct", sideB.dailyAvg() > 0 ? round(value / sideB.dailyAvg() * 100) : null);
            if (sideB.dailyAvg() <= 0) diff.put("pctNote", "基期为零");
            diff.put("basis", "dailyAvg");
            diff.put("unit", "度/日");
        } else {
            double value = round(sideA.total() - sideB.total());
            diff.put("value", value);
            diff.put("pct", sideB.total() > 0 ? round(value / sideB.total() * 100) : null);
            if (sideB.total() <= 0) diff.put("pctNote", "基期为零");
            diff.put("basis", "total");
            diff.put("unit", "度");
        }
        List<Map<String, Object>> metrics = comparisonMetrics(sideA, sideB, isDay);
        Map<String, Object> contribution = contribution(scope, combined, cohort, unequalSpan, effDaysA, effDaysB);
        Map<String, Object> context = comparisonContext(scope, scopeRows, combined, cohort, sideA, effDaysA);
        List<Map<String, Object>> meterRows = scopeRows.stream().map(row -> comparisonRowMap(row)).toList();
        List<String> noData = scopeRows.stream().filter(row -> "noData".equals(row.a().status()) || "noData".equals(row.b().status()))
                .map(ComparisonRow::id).toList();
        List<Map<String, Object>> partial = scopeRows.stream()
                .filter(row -> !cohort.contains(row) && !noData.contains(row.id()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.id());
                    String through = row.a().observedTo() == null ? null : String.valueOf(row.a().observedTo().get("readTime"));
                    String baseThrough = row.b().observedTo() == null ? null : String.valueOf(row.b().observedTo().get("readTime"));
                    if (through != null && baseThrough != null) through = through.compareTo(baseThrough) >= 0 ? through : baseThrough;
                    else if (through == null) through = baseThrough;
                    value.put("through", through);
                    return value;
                }).toList();
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("meters", scopeRows.size());
        coverage.put("dataMeters", cohort.size());
        coverage.put("noData", noData);
        coverage.put("partial", partial);
        coverage.put("addedMeterIds", scopeRows.stream()
                .filter(row -> "ok".equals(row.a().status()) && "noData".equals(row.b().status()))
                .map(ComparisonRow::id).toList());
        coverage.put("removedMeterIds", scopeRows.stream()
                .filter(row -> "ok".equals(row.b().status()) && "noData".equals(row.a().status()))
                .map(ComparisonRow::id).toList());
        coverage.put("truncated", stream.truncated());
        coverage.put("latestSampleAt", stream.latest() == null ? null : format(stream.latest()));
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        span.put("observed", observed(scopeRows.stream().map(ComparisonRow::a).toList()));
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("requested", Map.of("from", format(baseFrom), "to", format(baseTo)));
        baseline.put("observed", observed(scopeRows.stream().map(ComparisonRow::b).toList()));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("axis", "time");
        params.put("relation", timeRelation(from, to, baseFrom, baseTo));
        params.put("span", span);
        params.put("baseline", baseline);
        params.put("scope", scopeMap(scope));
        List<Map<String, Object>> sides = new ArrayList<>();
        Map<String, Object> sideAView = new LinkedHashMap<>();
        sideAView.put("total", totalView(sideA.total()));
        sideAView.put("dataMeters", sideA.dataMeters());
        sideAView.put("effectiveDays", round(effDaysA));
        Map<String, Object> shapeA = new LinkedHashMap<>();
        shapeA.put("rates", sideA.rates());
        shapeA.put("coveredFraction", round(sideA.coveredFraction()));
        sideAView.put("shape24", shapeA);
        sideAView.put("buckets", Map.of("grain", isDay ? "hour" : "day", "items", isDay ? sideA.hourBuckets() : sideA.dayBuckets()));
        Map<String, Object> sideBView = new LinkedHashMap<>();
        sideBView.put("total", totalView(sideB.total()));
        sideBView.put("dataMeters", sideB.dataMeters());
        sideBView.put("effectiveDays", round(effDaysB));
        Map<String, Object> shapeB = new LinkedHashMap<>();
        shapeB.put("rates", sideB.rates());
        shapeB.put("coveredFraction", round(sideB.coveredFraction()));
        sideBView.put("shape24", shapeB);
        sideBView.put("buckets", Map.of("grain", isDay ? "hour" : "day", "items", isDay ? sideB.hourBuckets() : sideB.dayBuckets()));
        Map<String, Object> sidesMap = new LinkedHashMap<>();
        sidesMap.put("a", sideAView);
        sidesMap.put("b", sideBView);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-meter-comparison-" + Integer.toHexString(Objects.hash(
                identity.ownerKey(), scope.level(), from, to, baseFrom, baseTo)));
        payload.put("type", "meter-report");
        payload.put("kind", "comparison");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        payload.put("params", params);
        payload.put("sides", sidesMap);
        payload.put("diff", diff);
        payload.put("metrics", metrics);
        payload.put("contribution", contribution);
        payload.put("context", context);
        payload.put("coverage", coverage);
        payload.put("meters", meterRows);
        payload.put("authorizationMeterIds", all.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    private static List<Map<String, Object>> comparisonMetrics(Aggregate a, Aggregate b, boolean isDay) {
        List<Map<String, Object>> metrics = new ArrayList<>();
        if (isDay) {
            metrics.add(metricComparison("dayTotal", "度", a.dayTotal(), b.dayTotal()));
        } else {
            metrics.add(metricComparison("dailyAvg", "度/日", a.dailyAvg(), b.dailyAvg()));
            metrics.add(metricComparison("weekdayDailyAvg", "度/日", a.weekdayDailyAvg(), b.weekdayDailyAvg()));
        }
        Map<String, Object> night = metricComparison("nightBase", "度/时", a.nightBase(), b.nightBase());
        night.put("nightWindow", "20:00-08:00");
        metrics.add(night);
        if (isDay) {
            metrics.add(metricComparison("peakHour", "度", a.peakHour(),
                    b.peakHour()));
        } else {
            Map<String, Object> peak = metricComparison("peakDay", "度", a.peakDay(),
                    b.peakDay());
            peak.put("dayWindow", "previous08:00-08:00");
            metrics.add(peak);
        }
        return metrics;
    }

    private static Map<String, Object> metricComparison(String key, String unit, Double a, Double b) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("unit", unit);
        Map<String, Object> av = new LinkedHashMap<>();
        av.put("value", a == null ? null : round(a));
        Map<String, Object> bv = new LinkedHashMap<>();
        bv.put("value", b == null ? null : round(b));
        result.put("a", av);
        result.put("b", bv);
        result.put("change", a != null && b != null ? round(a - b) : null);
        return result;
    }

    private Map<String, Object> contribution(Scope scope, List<ComparisonRow> combined, List<ComparisonRow> cohort,
            boolean unequal, double effDaysA, double effDaysB) {
        List<ComparisonRow> source = cohort;
        String highlight = null;
        if ("location".equals(scope.level())) {
            ComparisonRow target = combined.stream().filter(row -> scope.ids().contains(row.id())).findFirst().orElse(null);
            if (target != null) {
                String floor = target.floor();
                source = combined.stream()
                        .filter(row -> Objects.equals(floor, row.floor()) && "ok".equals(row.a().status()) && "ok".equals(row.b().status()))
                        .toList();
                highlight = target.areaName().isBlank() ? target.name() : target.areaName();
            }
        }
        Map<String, double[]> groups = new LinkedHashMap<>();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        for (ComparisonRow row : source) {
            String label = row.areaName().isBlank() ? row.name() : row.areaName();
            double delta = unequal
                    ? row.a().value() / effDaysA - row.b().value() / effDaysB
                    : row.a().value() - row.b().value();
            groups.computeIfAbsent(label, key -> new double[1])[0] += delta;
            ids.computeIfAbsent(label, key -> new ArrayList<>()).add(row.id());
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", entry.getKey());
            row.put("delta", round(entry.getValue()[0]));
            row.put("meterIds", ids.get(entry.getKey()));
            if (entry.getKey().equals(highlight)) row.put("highlight", true);
            all.add(row);
        }
        all.sort((left, right) -> Double.compare(Math.abs(((Number) right.get("delta")).doubleValue()),
                Math.abs(((Number) left.get("delta")).doubleValue())));
        List<Map<String, Object>> rows = new ArrayList<>(all.subList(0, Math.min(8, all.size())));
        String visibleHighlight = highlight;
        if (visibleHighlight != null && rows.stream().noneMatch(row -> visibleHighlight.equals(row.get("label")))) {
            all.stream().filter(row -> visibleHighlight.equals(row.get("label"))).findFirst().ifPresent(row -> rows.add(new LinkedHashMap<>(row)));
        }
        Map<String, Object> rest = null;
        if (all.size() > rows.size()) {
            double sum = all.stream().filter(row -> rows.stream().noneMatch(kept -> kept.get("label").equals(row.get("label"))))
                    .mapToDouble(row -> ((Number) row.get("delta")).doubleValue()).sum();
            rest = Map.of("count", all.size() - rows.size(), "delta", round(sum));
        }
        double sumAbs = all.stream().mapToDouble(row -> Math.abs(((Number) row.get("delta")).doubleValue())).sum();
        double top3 = all.stream().limit(3).mapToDouble(row -> Math.abs(((Number) row.get("delta")).doubleValue())).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", unequal ? "度/日" : "度");
        result.put("rows", rows);
        result.put("rest", rest);
        result.put("topShare", sumAbs > 0 ? round(top3 / sumAbs * 100) : null);
        return result;
    }

    private Map<String, Object> comparisonContext(Scope scope, List<ComparisonRow> scopeRows,
            List<ComparisonRow> combined, List<ComparisonRow> cohort, Aggregate sideA, double effDaysA) {
        if ("all".equals(scope.level())) {
            return floorSplit(combined.stream().map(row -> row.a()).toList());
        }
        if ("location".equals(scope.level())) {
            ComparisonRow target = scopeRows.get(0);
            List<ComparisonRow> floorRows = combined.stream().filter(row -> Objects.equals(row.floor(), target.floor())).toList();
            List<ComparisonRow> floorCohort = floorRows.stream()
                    .filter(row -> "ok".equals(row.a().status()) && "ok".equals(row.b().status())).toList();
            double floorTotal = floorCohort.stream().mapToDouble(row -> row.a().value()).sum();
            Map<String, Double> values = new LinkedHashMap<>();
            for (ComparisonRow row : floorCohort) {
                values.merge(row.areaName().isBlank() ? row.name() : row.areaName(), row.a().value(), Double::sum);
            }
            List<Map.Entry<String, Double>> ranked = values.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList();
            int rank = 1;
            for (Map.Entry<String, Double> entry : ranked) {
                if (entry.getKey().equals(target.areaName().isBlank() ? target.name() : target.areaName())) break;
                rank++;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", "peer");
            result.put("parentLabel", target.floor() == null ? "本层" : target.floor());
            result.put("share", floorTotal > 0 ? round(target.a().value() / floorTotal * 100) : null);
            result.put("rank", rank > ranked.size() ? null : rank);
            result.put("of", ranked.size());
            return result;
        }
        List<ComparisonRow> parent = combined.stream()
                .filter(row -> "ok".equals(row.a().status()) && "ok".equals(row.b().status())).toList();
        double parentTotal = parent.stream().mapToDouble(row -> row.a().value()).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "parent");
        result.put("parentLabel", "全部电表");
        result.put("share", parentTotal > 0 ? round(sideA.total() / parentTotal * 100) : null);
        result.put("perMeterDaily", cohort.isEmpty() ? null : round(sideA.total() / (cohort.size() * effDaysA)));
        result.put("parentPerMeterDaily", parent.isEmpty() ? null : round(parentTotal / (parent.size() * effDaysA)));
        return result;
    }

    // ------------------------------------------------------------------
    // Region comparison: same window, different scopes.
    // ------------------------------------------------------------------

    private Map<String, Object> regionComparison(RequestIdentity identity, Scope scopeA, Scope scopeB,
            List<AuthorizedDevice> all, StreamResult stream, Instant from, Instant to) {
        Map<String, List<Sample>> byMeter = stream.byMeter();
        Instant effTo = effectiveEndOf(all, byMeter, from, to);
        double effSpan = Math.max(HOUR_MS, effTo.toEpochMilli() - from.toEpochMilli());
        double effDays = effSpan / (double) DAY_MS;
        List<StatisticsRow> catalogRows = new ArrayList<>();
        for (AuthorizedDevice device : all) catalogRows.add(statisticsRow(identity, device, byMeter, from, to, effTo));
        Map<String, StatisticsRow> byId = new LinkedHashMap<>();
        for (StatisticsRow row : catalogRows) byId.put(row.id(), row);
        RegionSide sideA = regionSide(scopeA, byId, byMeter, from, to, effTo, effDays);
        RegionSide sideB = regionSide(scopeB, byId, byMeter, from, to, effTo, effDays);
        boolean normalize = sideA.ok() != sideB.ok() || !scopeA.level().equals(scopeB.level())
                || sideA.meters().size() != sideB.meters().size();
        List<Object> ratesA = sideA.rates(), ratesB = sideB.rates();
        if (normalize) {
            ratesA = perMeterRates(sideA);
            ratesB = perMeterRates(sideB);
        }
        double perMeterA = sideA.ok() > 0 ? sideA.total() / (sideA.ok() * effDays) : 0;
        double perMeterB = sideB.ok() > 0 ? sideB.total() / (sideB.ok() * effDays) : 0;
        Map<String, Object> diff = new LinkedHashMap<>();
        if (sideA.ok() == 0 || sideB.ok() == 0) {
            diff.put("value", null);
            diff.put("pct", null);
            diff.put("pctNote", "数据不足");
        } else if (normalize) {
            double value = round(perMeterA - perMeterB);
            diff.put("value", value);
            diff.put("pct", perMeterB > 0 ? round(value / perMeterB * 100) : null);
            if (perMeterB <= 0) diff.put("pctNote", "对照范围为零");
            diff.put("basis", "perMeterDaily");
            diff.put("unit", "度/台·日");
        } else {
            double value = round(sideA.total() - sideB.total());
            diff.put("value", value);
            diff.put("pct", sideB.total() > 0 ? round(value / sideB.total() * 100) : null);
            if (sideB.total() <= 0) diff.put("pctNote", "对照范围为零");
            diff.put("basis", "total");
            diff.put("unit", "度");
        }
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metricComparison("perMeterDaily", "度/台·日",
                sideA.ok() > 0 ? round(perMeterA) : null,
                sideB.ok() > 0 ? round(perMeterB) : null));
        boolean isDay = to.toEpochMilli() - from.toEpochMilli() <= DAY_MS;
        metrics.add(metricComparison(isDay ? "dayTotal" : "dailyAvg", isDay ? "度" : "度/日",
                isDay ? sideA.dayTotal() : sideA.dailyAvg(), isDay ? sideB.dayTotal() : sideB.dailyAvg()));
        metrics.add(metricComparison("nightBase", "度/时", sideA.nightBase(), sideB.nightBase()));
        metrics.add(metricComparison(isDay ? "peakHour" : "peakDay", "度",
                isDay ? sideA.peakHour() : sideA.peakDay(), isDay ? sideB.peakHour() : sideB.peakDay()));
        List<StatisticsRow> unionRows = new ArrayList<>();
        for (StatisticsRow row : catalogRows) {
            if (scopeA.ids().contains(row.id()) || scopeB.ids().contains(row.id())) unionRows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", "local-meter-region-" + Integer.toHexString(Objects.hash(
                identity.ownerKey(), scopeA.level(), scopeB.level(), from, to)));
        payload.put("type", "meter-report");
        payload.put("kind", "comparison");
        payload.put("source", "company");
        payload.put("observedAt", clock.instant().toString());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("axis", "region");
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("requested", Map.of("from", format(from), "to", format(to)));
        span.put("observed", observed(unionRows.stream().filter(row -> "ok".equals(row.status())).toList()));
        params.put("span", span);
        params.put("scope", scopeMap(scopeA));
        params.put("other", scopeMap(scopeB));
        payload.put("params", params);
        Map<String, Object> sides = new LinkedHashMap<>();
        sides.put("a", sideView(sideA, ratesA, effDays, true));
        sides.put("b", sideView(sideB, ratesB, effDays, true));
        payload.put("sides", sides);
        payload.put("diff", diff);
        payload.put("normalized", normalize);
        payload.put("metrics", metrics);
        List<String> overlap = scopeA.ids().stream().filter(scopeB.ids()::contains).toList();
        payload.put("overlapMeterIds", overlap);
        Map<String, Object> coverage = new LinkedHashMap<>();
        List<String> noData = unionRows.stream().filter(row -> "noData".equals(row.status())).map(StatisticsRow::id).toList();
        List<Map<String, Object>> partial = unionRows.stream().filter(row -> "partial".equals(row.status()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.id());
                    value.put("through", row.observedTo() == null ? null : row.observedTo().get("readTime"));
                    return value;
                }).toList();
        coverage.put("meters", unionRows.size());
        coverage.put("dataMeters", unionRows.stream().filter(row -> "ok".equals(row.status())).count());
        coverage.put("noData", noData);
        coverage.put("partial", partial);
        coverage.put("truncated", stream.truncated());
        coverage.put("latestSampleAt", stream.latest() == null ? null : format(stream.latest()));
        payload.put("coverage", coverage);
        payload.put("meters", unionRows.stream().map(CompanyEnergyObservationComposerImpl::statisticsRowMap).toList());
        payload.put("authorizationMeterIds", all.stream().map(AuthorizedDevice::id).toList());
        return payload;
    }

    private Map<String, Object> sideView(RegionSide side, List<Object> rates, double effDays, boolean normalized) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("level", side.level());
        scope.put("label", side.label());
        scope.put("basis", side.basis());
        scope.put("meterCount", side.meters().size());
        result.put("scope", scope);
        result.put("total", totalView(side.total()));
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("rates", rates);
        shape.put("coveredFraction", side.coveredFraction());
        if (normalized) shape.put("perMeter", true);
        result.put("shape24", shape);
        result.put("buckets", Map.of("grain", "day", "items", side.dayBuckets()));
        Map<String, Object> coverage = new LinkedHashMap<>();
        List<String> noData = side.meters().stream().filter(row -> "noData".equals(row.status())).map(StatisticsRow::id).toList();
        List<Map<String, Object>> partial = side.meters().stream().filter(row -> "partial".equals(row.status()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.id());
                    value.put("through", row.observedTo() == null ? null : row.observedTo().get("readTime"));
                    return value;
                }).toList();
        coverage.put("meters", side.meters().size());
        coverage.put("dataMeters", side.ok());
        coverage.put("noData", noData);
        coverage.put("partial", partial);
        result.put("coverage", coverage);
        result.put("meters", side.meters().stream().map(CompanyEnergyObservationComposerImpl::statisticsRowMap).toList());
        Map<String, Object> composition = new LinkedHashMap<>();
        composition.put("kind", "ranking");
        composition.put("rows", side.composition());
        composition.put("rest", null);
        composition.put("excluded", excluded(side.meters()));
        result.put("composition", composition);
        result.put("context", side.context());
        return result;
    }

    private static List<Object> perMeterRates(RegionSide side) {
        if (side.rates() == null || side.ok() == 0) return null;
        List<Object> rates = new ArrayList<>();
        for (Object value : side.rates()) {
            rates.add(value instanceof Number number ? round(number.doubleValue() / side.ok()) : null);
        }
        return rates;
    }

    private RegionSide regionSide(Scope scope, Map<String, StatisticsRow> byId,
            Map<String, List<Sample>> byMeter, Instant from, Instant to, Instant effTo, double effDays) {
        List<StatisticsRow> rows = scope.ids().stream().map(byId::get).filter(Objects::nonNull).toList();
        List<StatisticsRow> ok = rows.stream().filter(row -> "ok".equals(row.status())).toList();
        Aggregate aggregate = sideAggregate(
                ok.stream().map(row -> new RowValue(row.id(), row.value())).toList(),
                byMeter, from, to, effTo);
        List<Map<String, Object>> composition = groupLocations(ok);
        Map<String, Object> context;
        if ("all".equals(scope.level())) {
            context = floorSplit(rows);
        } else if ("location".equals(scope.level())) {
            StatisticsRow target = rows.get(0);
            List<StatisticsRow> floorRows = byId.values().stream()
                    .filter(row -> Objects.equals(row.floor(), target.floor())).toList();
            List<StatisticsRow> floorOk = floorRows.stream().filter(row -> "ok".equals(row.status())).toList();
            List<Map<String, Object>> floorLocations = groupLocations(floorOk);
            double floorTotal = floorOk.stream().mapToDouble(StatisticsRow::value).sum();
            int rank = 0;
            String label = target.areaName().isBlank() ? target.name() : target.areaName();
            for (int index = 0; index < floorLocations.size(); index++) {
                if (label.equals(floorLocations.get(index).get("label"))) { rank = index + 1; break; }
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", "peer");
            value.put("parentLabel", target.floor() == null ? "本层" : target.floor());
            value.put("share", floorTotal > 0 ? round(target.value() / floorTotal * 100) : null);
            value.put("rank", rank == 0 ? null : rank);
            value.put("of", floorLocations.size());
            context = value;
        } else {
            List<StatisticsRow> parentOk = byId.values().stream().filter(row -> "ok".equals(row.status())).toList();
            double parentTotal = parentOk.stream().mapToDouble(StatisticsRow::value).sum();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("kind", "parent");
            value.put("parentLabel", "全部电表");
            value.put("share", parentTotal > 0 ? round(aggregate.total() / parentTotal * 100) : null);
            value.put("perMeterDaily", ok.isEmpty() ? null : round(aggregate.total() / (ok.size() * effDays)));
            value.put("parentPerMeterDaily", parentOk.isEmpty() ? null : round(parentTotal / (parentOk.size() * effDays)));
            context = value;
        }
        return new RegionSide(scope.level(), scope.label(), scope.basis(), rows, ok,
                aggregate.total(), aggregate.dataMeters(), aggregate.coveredFraction(),
                aggregate.rates(), aggregate.dayBuckets(), composition, context,
                aggregate.dailyAvg(), aggregate.dayTotal(), aggregate.nightBase(),
                aggregate.peakDay(),
                aggregate.peakHour());
    }

    // ------------------------------------------------------------------
    // Generic report envelope used by /energy-report and report evidence.
    // ------------------------------------------------------------------

    private Mono<ObservationResponse> energyReport(ObservationRequest request, List<AuthorizedDevice> meters) {
        Map<String, String> p = new LinkedHashMap<>(request.parameters());
        String kind = p.getOrDefault("kind", "statistics");
        if (!List.of("statistics", "comparison", "inspection").contains(kind)) {
            return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "请选择统计、对比或检查", 400));
        }
        if (p.containsKey("equipmentIds")) {
            p.put("scopeLevel", "selection");
            p.put("meterIds", p.remove("equipmentIds"));
        }
        if (p.containsKey("baselineFrom")) p.put("baseFrom", p.remove("baselineFrom"));
        if (p.containsKey("baselineTo")) p.put("baseTo", p.remove("baselineTo"));
        if ("comparison".equals(kind) && p.containsKey("periods")) {
            try {
                List<Map<String, Object>> values = new ObjectMapper().readValue(p.get("periods"), new TypeReference<>() { });
                if (values.size() != 2) throw new IllegalArgumentException();
                Map<String, Object> base = values.get(0), current = values.get(1);
                p.put("from", String.valueOf(current.get("from")));
                p.put("to", String.valueOf(current.get("to")));
                p.put("baseFrom", String.valueOf(base.get("from")));
                p.put("baseTo", String.valueOf(base.get("to")));
            } catch (Exception error) {
                return Mono.error(new BusinessException("INVALID_ENERGY_WINDOW", "明确对比须为两段有效期间", 400));
            }
        }
        p.put("kind", "inspection".equals(kind) ? "statistics" : kind);
        if ("comparison".equals(p.get("kind"))) p.putIfAbsent("axis", "time");
        ObservationRequest mapped = new ObservationRequest(request.identity(), ObservationOperation.COMPANY_METER_REPORT, p);
        return meterReport(mapped, meters).map(response -> {
            if (!"inspection".equals(kind)) return response;
            Map<String, Object> payload = new LinkedHashMap<>(response.payload());
            payload.put("kind", "inspection");
            List<Map<String, Object>> checks = new ArrayList<>();
            List<Map<String, Object>> findings = new ArrayList<>();
            List<Map<String, Object>> notChecked = new ArrayList<>();
            Object coverage = payload.get("coverage");
            if (coverage instanceof Map<?, ?> value) {
                checks.add(Map.of("code", "coverage", "label", "请求边界与读取覆盖",
                        "status", Boolean.TRUE.equals(value.get("truncated")) ? "partial" : "complete"));
                Object noData = value.get("noData");
                if (noData instanceof List<?> ids) {
                    for (Object id : ids) {
                        findings.add(Map.of("code", "coverage", "meterId", String.valueOf(id),
                                "title", "没有取得有效采样", "detail", "该表不进入合计，不以零补齐", "period", 0,
                                "rule", "完整边界、无回退/同时间冲突且读取覆盖完整"));
                    }
                }
            }
            double threshold = 0;
            if (p.containsKey("growthThreshold")) {
                try {
                    threshold = Double.parseDouble(p.get("growthThreshold"));
                    if (!Double.isFinite(threshold) || threshold < 0) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    return energyResponse(Map.of("type", "meter-report", "kind", "inspection",
                            "source", "company", "checks", List.of(), "notChecked", List.of(),
                            "findings", List.of(), "error", "非负百分比阈值无效"));
                }
            }
            payload.put("checks", checks);
            payload.put("findings", findings);
            payload.put("notChecked", notChecked);
            Map<String, Object> rules = new LinkedHashMap<>();
            rules.put("growthThreshold", p.containsKey("growthThreshold") ? threshold : null);
            payload.put("rules", rules);
            return energyResponse(payload);
        });
    }

    // ------------------------------------------------------------------
    // Stream reading.
    // ------------------------------------------------------------------

    private Mono<StreamResult> readStream(RequestIdentity identity, Set<String> meterIds,
            Instant stopBefore, Instant endAt) {
        return fetchPage(identity, 0)
                .onErrorResume(error -> isFatalEnergyError(error) ? Mono.error(error) : Mono.just(PageData.failure(0)))
                .flatMap(first -> {
                    StreamAccumulator accumulator = new StreamAccumulator(meterIds, stopBefore, endAt, maxRows);
                    accumulator.add(first);
                    if (accumulator.stop()) return Mono.just(accumulator.result());
                    return readStreamPages(identity, 1, accumulator);
                });
    }

    private Mono<StreamResult> readStreamPages(RequestIdentity identity, int page, StreamAccumulator accumulator) {
        if (page >= maxPages || clock.millis() >= accumulator.deadlineAt()) {
            accumulator.truncate();
            return Mono.just(accumulator.result());
        }
        return fetchPage(identity, page)
                .onErrorResume(error -> isFatalEnergyError(error) ? Mono.error(error) : Mono.just(PageData.failure(page)))
                .flatMap(data -> {
                    accumulator.add(data);
                    if (accumulator.stop() || data.error()) return Mono.just(accumulator.result());
                    return readStreamPages(identity, page + 1, accumulator);
                });
    }

    private Mono<PageData> fetchPage(RequestIdentity identity, int page) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", Integer.toString(page));
        params.put("size", Integer.toString(PAGE_SIZE));
        return rawHistory.read(new ObservationRequest(identity, ObservationOperation.ENERGY_HISTORY, params))
                .flatMap(response -> {
                    if (response.status() == ObservationStatus.UNAVAILABLE) {
                        return Mono.error(new ObservationUnavailableException("计量样本流上游不可用"));
                    }
                    if (response.status() != ObservationStatus.AVAILABLE
                            && response.status() != ObservationStatus.EMPTY
                            && response.status() != ObservationStatus.PARTIAL) {
                        return Mono.error(new ObservationUnavailableException("计量样本流返回不可用结果"));
                    }
                    List<StreamRow> rows = response.status() == ObservationStatus.EMPTY
                            ? List.of() : streamRows(response.payload());
                    return Mono.just(new PageData(page, rows, isLast(response.payload()), false));
                })
                .onErrorMap(error -> error instanceof BusinessException
                        ? error : new ObservationUnavailableException("计量样本流读取失败"));
    }

    private static List<StreamRow> streamRows(Map<String, Object> payload) {
        Object raw = payload.get("samples");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (raw instanceof List<?> values) {
            for (Object value : values) if (value instanceof Map<?, ?> map) rows.add(castMap(map));
        } else {
            Object page = payload.get("content");
            if (page instanceof Map<?, ?> nested) page = nested.get("content");
            if (page == null) page = payload.get("rows");
            if (page == null) page = payload.get("items");
            if (!(page instanceof List<?> values)) return List.of();
            for (Object value : values) if (value instanceof Map<?, ?> map) rows.add(castMap(map));
        }
        List<StreamRow> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String meterId = text(row.get("equipmentId"));
            String readTime = text(row.get("readTime"));
            Instant at = instantOrNull(readTime);
            if (readTime != null && at == null) {
                throw new BusinessException("INVALID_ENERGY_SAMPLE", "计量样本时间格式无效", 400);
            }
            if (meterId == null || at == null) continue;
            Object rawValue = row.get("epf");
            if (rawValue == null && row.get("values") instanceof Map<?, ?> values) rawValue = values.get("epf");
            Double value = null;
            if (rawValue != null) {
                try { value = number(rawValue); }
                catch (RuntimeException error) { throw new BusinessException("INVALID_ENERGY_SAMPLE", "累计示数格式无效", 400); }
            }
            result.add(new StreamRow(meterId, text(row.get("id")), at, value));
        }
        result.sort(Comparator.comparing(StreamRow::readTime));
        return result;
    }

    private final class StreamAccumulator {
        private final Set<String> meterIds;
        private final Instant stopBefore;
        private final Instant endAt;
        private final int rowLimit;
        private final long deadlineAt;
        private final Map<String, List<Sample>> byMeter = new LinkedHashMap<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private Instant latest;
        private Instant previousOldest;
        private int rowCount;
        private int pageCount;
        private boolean crossed;
        private boolean orderBroken;
        private boolean lastSeen;
        private boolean truncated;

        private StreamAccumulator(Set<String> meterIds, Instant stopBefore, Instant endAt, int rowLimit) {
            this.meterIds = meterIds;
            this.stopBefore = stopBefore;
            this.endAt = endAt;
            this.rowLimit = rowLimit;
            this.deadlineAt = clock.millis() + READ_BUDGET_MS;
        }

        private long deadlineAt() { return deadlineAt; }

        private void add(PageData page) {
            pageCount++;
            if (page.last()) lastSeen = true;
            if (page.error()) {
                truncated = true;
                return;
            }
            if (page.rows().isEmpty()) {
                if (!page.last()) truncated = true;
                return;
            }
            Instant pageNewest = page.rows().get(page.rows().size() - 1).readTime();
            Instant pageOldest = page.rows().get(0).readTime();
            if (previousOldest != null && pageNewest.isAfter(previousOldest)) {
                orderBroken = true;
                truncated = true;
            }
            previousOldest = pageOldest;
            for (StreamRow row : page.rows()) {
                rowCount++;
                if (latest == null || row.readTime().isAfter(latest)) latest = row.readTime();
                if (row.readTime().isBefore(stopBefore)) {
                    crossed = true;
                    continue;
                }
                if (row.readTime().isAfter(endAt) || row.value() == null || !meterIds.contains(row.meterId())) continue;
                String key = row.meterId() + "\u0000" + row.readTime() + "\u0000" + row.value();
                if (!seen.add(key)) continue;
                byMeter.computeIfAbsent(row.meterId(), keyValue -> new ArrayList<>())
                        .add(new Sample(row.id(), row.readTime(), row.value()));
            }
            if (rowCount >= rowLimit) truncated = true;
        }

        private boolean stop() {
            return lastSeen || orderBroken || crossed || truncated || pageCount >= maxPages
                    || rowCount >= rowLimit || clock.millis() >= deadlineAt;
        }

        private void truncate() { truncated = true; }

        private StreamResult result() {
            List<Map.Entry<String, List<Sample>>> entries = new ArrayList<>(byMeter.entrySet());
            Map<String, List<Sample>> sorted = new LinkedHashMap<>();
            for (Map.Entry<String, List<Sample>> entry : entries) {
                List<Sample> samples = new ArrayList<>(entry.getValue());
                samples.sort(Comparator.comparing(Sample::readTime));
                sorted.put(entry.getKey(), samples);
            }
            return new StreamResult(sorted, latest, truncated || orderBroken);
        }
    }

    // ------------------------------------------------------------------
    // Interval arithmetic.
    // ------------------------------------------------------------------

    private static PeriodFact comparisonPeriod(List<Sample> samples, Instant from, Instant to, boolean complete) {
        List<Sample> rows = samples.stream()
                .filter(sample -> !sample.readTime().isBefore(from) && !sample.readTime().isAfter(to))
                .toList();
        Sample first = rows.isEmpty() ? null : rows.get(0);
        Sample last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        boolean conflict = false, reset = false;
        for (int index = 1; index < rows.size(); index++) {
            Sample previous = rows.get(index - 1), current = rows.get(index);
            conflict |= current.readTime().equals(previous.readTime()) && current.value() != previous.value();
            reset |= current.value() < previous.value();
        }
        List<String> notes = new ArrayList<>();
        Double usage = null;
        String status = "not_computable";
        if (conflict) notes.add("同一时刻存在冲突示数");
        else if (reset) notes.add("累计示数回退，可能清零或换表，不计算增量");
        else if (first == null || last == null || first.readTime().equals(last.readTime())) {
            notes.add("不足两个不同时间的有效采样");
        } else {
            usage = round(last.value() - first.value());
            status = first.readTime().equals(from) && last.readTime().equals(to) && complete
                    ? "comparable" : "partial";
            if (!first.readTime().equals(from) || !last.readTime().equals(to)) {
                notes.add("边界缺采样，仅显示实际缩短区间的示数增量，不估算整段");
            }
            if (!complete) notes.add("读取预算内未核验全部采样，暂不计算环比");
        }
        return new PeriodFact(from, to, first, last, usage, status, notes);
    }

    private static WindowResult meterWindow(List<Sample> samples, Instant from, Instant to) {
        Sample fromSample = null, toSample = null;
        for (Sample sample : samples) {
            if (!sample.readTime().isAfter(from)) fromSample = sample;
            else if (fromSample == null && !sample.readTime().isAfter(from.plusMillis(12 * HOUR_MS))) fromSample = sample;
            if (!sample.readTime().isAfter(to)) toSample = sample;
        }
        if (fromSample == null || toSample == null) return new WindowResult(fromSample, toSample, null);
        Instant fromTime = fromSample.readTime(), toTime = toSample.readTime();
        if (!fromTime.isBefore(toTime)) return new WindowResult(fromSample, toSample, null);
        Instant lastTime = null;
        double lastValue = 0;
        boolean seen = false;
        for (Sample sample : samples) {
            Instant time = sample.readTime();
            if (time.isBefore(fromTime) || time.isAfter(toTime)) continue;
            if (seen && (time.equals(lastTime) ? sample.value() != lastValue : sample.value() < lastValue)) {
                return new WindowResult(fromSample, toSample, null);
            }
            lastTime = time;
            lastValue = sample.value();
            seen = true;
        }
        double value = toSample.value() - fromSample.value();
        return new WindowResult(fromSample, toSample, value >= 0 ? round(value) : null);
    }

    private static Double completeWindowEnergy(List<Sample> samples, Instant start, Instant end) {
        WindowResult exact = meterWindow(samples, start, end);
        if (exact.value() != null && exact.from() != null && exact.to() != null
                && exact.from().readTime().equals(start) && exact.to().readTime().equals(end)) {
            return exact.value();
        }
        Map<Long, Double> slots = new HashMap<>();
        for (Sample sample : samples) {
            long time = sample.readTime().toEpochMilli();
            if (slots.containsKey(time) && !Objects.equals(slots.get(time), sample.value())) slots.put(time, null);
            else slots.put(time, sample.value());
        }
        List<Map.Entry<Long, Double>> ordered = new ArrayList<>(slots.entrySet());
        ordered.sort(Map.Entry.comparingByKey());
        double energy = 0, covered = 0;
        for (int index = 1; index < ordered.size(); index++) {
            Map.Entry<Long, Double> previous = ordered.get(index - 1), current = ordered.get(index);
            if (current.getKey() <= start.toEpochMilli() || previous.getKey() >= end.toEpochMilli()) continue;
            if (previous.getValue() == null || current.getValue() == null
                    || current.getValue() < previous.getValue()
                    || current.getKey() - previous.getKey() > MAX_GAP_MS) return null;
            long overlap = Math.min(current.getKey(), end.toEpochMilli()) - Math.max(previous.getKey(), start.toEpochMilli());
            if (overlap <= 0) continue;
            energy += (current.getValue() - previous.getValue()) * overlap / (double) (current.getKey() - previous.getKey());
            covered += overlap;
        }
        return covered == end.toEpochMilli() - start.toEpochMilli() ? round(energy) : null;
    }

    private static Long bucketCellValue(List<Sample> samples, Instant from, Instant to) {
        boolean progress = samples.stream().anyMatch(sample ->
                sample.readTime().isAfter(from) && !sample.readTime().isAfter(to));
        if (!progress) return null;
        WindowResult window = meterWindow(samples, from, to);
        return window.value() == null ? null : Math.round(window.value() * 100);
    }

    private static Instant effectiveEndOf(List<AuthorizedDevice> devices, Map<String, List<Sample>> byMeter,
            Instant fromMs, Instant toMs) {
        Instant latest = null;
        for (AuthorizedDevice device : devices) {
            for (Sample sample : samples(byMeter, device.id())) {
                if (sample.readTime().isAfter(fromMs) && !sample.readTime().isAfter(toMs)
                        && (latest == null || sample.readTime().isAfter(latest))) latest = sample.readTime();
            }
        }
        return latest != null && toMs.toEpochMilli() - latest.toEpochMilli() > DAY_MS ? latest : toMs;
    }

    private static Double nightAverage(List<StatisticsRow> rows, Map<String, List<Sample>> byMeter, Instant from, Instant to) {
        if (rows.isEmpty()) return null;
        long start = floorDiv(from.toEpochMilli() + 8 * HOUR_MS, DAY_MS) * DAY_MS - 8 * HOUR_MS + 20 * HOUR_MS;
        double energy = 0;
        int nights = 0;
        for (; start + 12 * HOUR_MS <= to.toEpochMilli(); start += DAY_MS) {
            boolean complete = true;
            double nightEnergy = 0;
            for (StatisticsRow row : rows) {
                Double value = completeWindowEnergy(samples(byMeter, row.id()),
                        Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + 12 * HOUR_MS));
                if (value == null) { complete = false; break; }
                nightEnergy += value;
            }
            if (complete) { energy += nightEnergy; nights++; }
        }
        return nights == 0 ? null : round(energy / (nights * 12.0));
    }

    private static List<DayEnergy> meteringDayEnergy(List<StatisticsRow> rows, Map<String, List<Sample>> byMeter,
            Instant from, Instant to) {
        List<DayEnergy> result = new ArrayList<>();
        if (rows.isEmpty()) return result;
        long start = ceilDiv(from.toEpochMilli(), DAY_MS) * DAY_MS;
        for (; start + DAY_MS <= to.toEpochMilli(); start += DAY_MS) {
            boolean complete = true;
            double energy = 0;
            for (StatisticsRow row : rows) {
                Double value = completeWindowEnergy(samples(byMeter, row.id()),
                        Instant.ofEpochMilli(start), Instant.ofEpochMilli(start + DAY_MS));
                if (value == null) { complete = false; break; }
                energy += value;
            }
            if (complete) result.add(new DayEnergy(LocalDate.ofInstant(Instant.ofEpochMilli(start + DAY_MS), SHANGHAI).toString(), energy));
        }
        return result;
    }

    private static long hourlyAllocation(List<Sample> samples, long from, long to,
            double[] hourEnergy, double[] hourCovered,
            Map<Long, Double> slotEnergy, Map<Long, Double> slotCovered) {
        long coveredMs = 0;
        for (int index = 1; index < samples.size(); index++) {
            Sample previous = samples.get(index - 1), current = samples.get(index);
            long t0 = previous.readTime().toEpochMilli(), t1 = current.readTime().toEpochMilli();
            long dt = t1 - t0;
            double de = current.value() - previous.value();
            if (dt > MAX_GAP_MS || de < 0 || dt <= 0) continue;
            long lo = Math.max(t0, from), hi = Math.min(t1, to);
            if (hi <= lo) continue;
            long cursor = lo;
            while (cursor < hi) {
                long hourStart = floorDiv(cursor + 8 * HOUR_MS, HOUR_MS) * HOUR_MS - 8 * HOUR_MS;
                long end = Math.min(hourStart + HOUR_MS, hi);
                double share = (end - cursor) / (double) dt;
                int hour = Instant.ofEpochMilli(cursor).atZone(SHANGHAI).getHour();
                hourEnergy[hour] += de * share;
                hourCovered[hour] += end - cursor;
                if (slotEnergy != null) slotEnergy.merge(hourStart, de * share, Double::sum);
                if (slotCovered != null) slotCovered.merge(hourStart, (double) (end - cursor), Double::sum);
                coveredMs += end - cursor;
                cursor = end;
            }
        }
        return coveredMs;
    }

    private static List<Map<String, Object>> boundaryBuckets(Instant from, Instant to, Instant effTo,
            String grain, List<StatisticsRow> valid, Map<String, List<Sample>> byMeter) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportWindow window : bucketWindows(from, to, grain)) {
            List<Long> cells = valid.stream()
                    .map(row -> bucketCellValue(samples(byMeter, row.id()), window.from(), window.to()))
                    .filter(Objects::nonNull).toList();
            double sum = cells.stream().mapToLong(value -> value).sum() / 100.0;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from()));
            bucket.put("to", format(window.to()));
            bucket.put("value", cells.isEmpty() ? null : round(sum));
            bucket.put("partial", !window.full() || cells.size() < valid.size() || window.to().isAfter(effTo));
            bucket.put("days", Math.max(1, Math.round((Math.min(window.to().toEpochMilli(), effTo.toEpochMilli())
                    - window.from().toEpochMilli()) / (double) DAY_MS)));
            result.add(bucket);
        }
        return result;
    }

    private static List<Map<String, Object>> hourBuckets(Instant from, Instant to, Instant effTo,
            List<StatisticsRow> valid, Map<String, List<Sample>> byMeter,
            Map<Long, Double> slotEnergy, Map<Long, Double> slotCovered, int dataMeters) {
        List<ReportWindow> windows = bucketWindows(from, to, "hour");
        if (windows.size() >= MAX_BUCKETS && windows.get(windows.size() - 1).to().isBefore(to)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportWindow window : windows) {
            long slotStart = floorDiv(window.from().toEpochMilli() + 8 * HOUR_MS, HOUR_MS) * HOUR_MS - 8 * HOUR_MS;
            double covered = slotCovered.getOrDefault(slotStart, 0.0);
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from()));
            bucket.put("to", format(window.to()));
            bucket.put("value", covered > 0 ? round(slotEnergy.getOrDefault(slotStart, 0.0)) : null);
            bucket.put("partial", !window.full() || covered < dataMeters * (window.to().toEpochMilli() - window.from().toEpochMilli()));
            bucket.put("days", 1);
            result.add(bucket);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Catalog / scope / presentation helpers.
    // ------------------------------------------------------------------

    private static Map<String, Object> scopeMap(Scope scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", scope.level());
        result.put("label", scope.label());
        result.put("basis", scope.basis());
        result.put("meterCount", scope.meters().size());
        return result;
    }

    /**
     * Primary scope keys are lower camel case ({@code scopeLevel}, {@code meterId}),
     * while a region-comparison counterpart prefixes the capitalised suffix
     * ({@code otherScopeLevel}, {@code otherMeterId}). Concatenating directly
     * would make the primary lookup miss and silently fall back to the whole
     * authorised catalog, so resolve the canonical key explicitly.
     */
    private static String scopeKey(String prefix, String name) {
        if (prefix.isEmpty()) return name;
        return prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private Scope resolveScope(Map<String, String> p, List<AuthorizedDevice> all, String prefix) {
        String level = p.getOrDefault(scopeKey(prefix, "scopeLevel"), "all");
        String label;
        String basis = "areaName";
        List<AuthorizedDevice> selected;
        switch (level) {
            case "all" -> {
                selected = all;
                label = "全部电表";
            }
            case "floor" -> {
                String floor = required(p, scopeKey(prefix, "floor"));
                if (p.containsKey(scopeKey(prefix, "text")) && !p.get(scopeKey(prefix, "text")).isBlank()) {
                    throw new BusinessException("INVALID_ENERGY_WINDOW",
                            "楼层计量范围不接受名称匹配词：请改用 nameMatch 并同时提供 floor 与 text", 400);
                }
                selected = all.stream().filter(device -> floor.equals(floorOf(device.installAddress()))).toList();
                label = floor;
            }
            case "location" -> {
                String id = required(p, scopeKey(prefix, "meterId"));
                AuthorizedDevice device = all.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
                if (device == null) throw new BusinessException("FORBIDDEN", "该计量点不在当前公司观察范围内", 403);
                selected = List.of(device);
                label = device.installAddress().isBlank() ? device.name() : device.installAddress();
                basis = "deviceIds";
            }
            case "nameMatch" -> {
                String text = required(p, scopeKey(prefix, "text"));
                String floor = p.get(scopeKey(prefix, "floor"));
                selected = all.stream().filter(device -> (floor == null || floor.equals(floorOf(device.installAddress())))
                        && ((device.installAddress() + " " + device.name()).contains(text))).toList();
                label = (floor == null ? "" : floor + " · ") + "按名称匹配「" + text + "」";
                basis = "nameMatch";
            }
            case "selection" -> {
                Set<String> ids = splitIds(p.get(scopeKey(prefix, "meterIds")));
                if (ids.isEmpty()) throw new BusinessException("INVALID_ENERGY_WINDOW", "计量范围参数不完整", 400);
                selected = ids.stream().map(id -> all.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null))
                        .toList();
                if (selected.stream().anyMatch(Objects::isNull)) {
                    throw new BusinessException("FORBIDDEN", "所选电表不在当前公司观察范围内", 403);
                }
                List<String> places = selected.stream().map(device -> {
                    String place = device.installAddress().isBlank() ? device.name() : device.installAddress();
                    return place;
                }).distinct().toList();
                label = places.size() == 1 ? places.get(0)
                        : String.join("、", places.subList(0, Math.min(3, places.size())))
                        + (places.size() > 3 ? " 等" + places.size() + "处" : "");
                basis = "deviceIds";
            }
            default -> throw new BusinessException("INVALID_ENERGY_WINDOW", "计量范围层级无效", 400);
        }
        if (selected.isEmpty()) throw new BusinessException("INVALID_ENERGY_WINDOW", "该计量范围内没有电表", 400);
        return new Scope(level, selected, label, basis);
    }

    private static List<Map<String, Object>> groupLocations(List<StatisticsRow> rows) {
        Map<String, LocationSum> sums = new LinkedHashMap<>();
        for (StatisticsRow row : rows) {
            if (!"ok".equals(row.status())) continue;
            String label = row.areaName().isBlank() ? row.name() : row.areaName();
            LocationSum sum = sums.computeIfAbsent(label, key -> new LocationSum(label));
            sum.value += row.value();
            sum.ids.add(row.id());
        }
        return sums.values().stream().sorted((left, right) -> Double.compare(right.value, left.value))
                .map(sum -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("label", sum.label);
                    row.put("value", round(sum.value));
                    row.put("meterIds", sum.ids);
                    return row;
                }).toList();
    }

    private static List<Map<String, Object>> groupNightLocations(List<NightMeter> rows) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        for (NightMeter row : rows) {
            if (row.usage() == null) continue;
            String label = row.row().areaName().isBlank() ? row.row().name() : row.row().areaName();
            sums.computeIfAbsent(label, key -> new double[1])[0] += row.usage();
            ids.computeIfAbsent(label, key -> new ArrayList<>()).add(row.row().id());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : sums.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", entry.getKey());
            row.put("value", round(entry.getValue()[0]));
            row.put("meterIds", ids.get(entry.getKey()));
            result.add(row);
        }
        result.sort((left, right) -> Double.compare(((Number) right.get("value")).doubleValue(),
                ((Number) left.get("value")).doubleValue()));
        return result.stream().limit(8).toList();
    }

    private static Map<String, Object> floorSplit(List<StatisticsRow> rows) {
        Map<String, FloorSum> floors = new LinkedHashMap<>();
        for (StatisticsRow row : rows) {
            String key = row.floor() == null ? "其他" : row.floor();
            FloorSum floor = floors.computeIfAbsent(key, value -> new FloorSum(value));
            floor.meters++;
            if ("ok".equals(row.status())) {
                floor.dataMeters++;
                floor.value += row.value();
            }
        }
        List<Map<String, Object>> values = floors.values().stream()
                .sorted((left, right) -> Double.compare(right.value, left.value))
                .map(floor -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("floor", floor.floor);
                    value.put("value", round(floor.value));
                    value.put("dataMeters", floor.dataMeters);
                    value.put("meters", floor.meters);
                    return value;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "floorSplit");
        result.put("floors", values);
        return result;
    }

    private static Map<String, Object> excluded(List<StatisticsRow> rows) {
        List<String> through = rows.stream().filter(row -> "partial".equals(row.status()))
                .map(row -> row.observedTo() == null ? "" : String.valueOf(row.observedTo().get("readTime")))
                .filter(value -> !value.isBlank()).sorted().toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("noData", rows.stream().filter(row -> "noData".equals(row.status())).count());
        result.put("partial", rows.stream().filter(row -> "partial".equals(row.status())).count());
        result.put("through", through.isEmpty() ? null : through.get(through.size() - 1));
        return result;
    }

    private static Map<String, Object> coverage(List<StatisticsRow> rows, boolean truncated, Instant latest) {
        List<String> noData = rows.stream().filter(row -> "noData".equals(row.status())).map(StatisticsRow::id).toList();
        List<Map<String, Object>> partial = rows.stream().filter(row -> "partial".equals(row.status()))
                .map(row -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", row.id());
                    value.put("through", row.observedTo() == null ? null : row.observedTo().get("readTime"));
                    return value;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meters", rows.size());
        result.put("dataMeters", rows.stream().filter(row -> "ok".equals(row.status())).count());
        result.put("noData", noData);
        result.put("partial", partial);
        result.put("truncated", truncated);
        result.put("latestSampleAt", latest == null ? null : format(latest));
        return result;
    }

    private static Map<String, Object> observed(List<StatisticsRow> rows) {
        List<StatisticsRow> valid = rows.stream().filter(row -> row.observedFrom() != null && row.observedTo() != null).toList();
        if (valid.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("from", null);
            empty.put("to", null);
            return empty;
        }
        Instant from = valid.stream().map(row -> instant(String.valueOf(row.observedFrom().get("readTime")))).min(Instant::compareTo).orElse(null);
        Instant to = valid.stream().map(row -> instant(String.valueOf(row.observedTo().get("readTime")))).max(Instant::compareTo).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", from == null ? null : format(from));
        result.put("to", to == null ? null : format(to));
        return result;
    }

    private StatisticsRow statisticsRow(RequestIdentity identity, AuthorizedDevice device,
            Map<String, List<Sample>> byMeter, Instant from, Instant to, Instant effTo) {
        List<Sample> samples = samples(byMeter, device.id());
        boolean inSpan = samples.stream().anyMatch(sample ->
                !sample.readTime().isBefore(from) && !sample.readTime().isAfter(to));
        WindowResult window = meterWindow(samples, from, to);
        long effSpan = Math.max(HOUR_MS, effTo.toEpochMilli() - from.toEpochMilli());
        boolean stale = window.to() == null || effTo.toEpochMilli() - window.to().readTime().toEpochMilli() > effSpan / 2;
        String status = !inSpan ? "noData" : window.value() == null || stale ? "partial" : "ok";
        double value = status.equals("ok") ? scaled(device, window.value(), identity) : 0;
        return new StatisticsRow(device.id(), device.name(), device.installAddress(), floorOf(device.installAddress()),
                value, status, sampleMap(window.from()), sampleMap(window.to()));
    }

    private static Map<String, Object> statisticsRowMap(StatisticsRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.id());
        result.put("name", row.name());
        result.put("areaName", row.areaName());
        result.put("floor", row.floor());
        result.put("value", "ok".equals(row.status()) ? row.value() : null);
        result.put("status", row.status());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("fromSample", row.observedFrom());
        observed.put("toSample", row.observedTo());
        result.put("observed", observed);
        return result;
    }

    private static Map<String, Object> comparisonRowMap(ComparisonRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.id());
        result.put("name", row.name());
        result.put("areaName", row.areaName());
        result.put("floor", row.floor());
        result.put("a", sideMap(row.a()));
        result.put("b", sideMap(row.b()));
        return result;
    }

    private static Map<String, Object> sideMap(StatisticsRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", "ok".equals(row.status()) ? row.value() : null);
        result.put("status", row.status());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("fromSample", row.observedFrom());
        observed.put("toSample", row.observedTo());
        result.put("observed", observed);
        return result;
    }

    private StatisticsRow sideStatus(RequestIdentity identity, AuthorizedDevice device,
            Map<String, List<Sample>> byMeter, Instant from, Instant to, Instant effTo) {
        List<Sample> samples = samples(byMeter, device.id());
        boolean inSpan = samples.stream().anyMatch(sample ->
                !sample.readTime().isBefore(from) && !sample.readTime().isAfter(to));
        WindowResult window = meterWindow(samples, from, to);
        long effSpan = Math.max(HOUR_MS, effTo.toEpochMilli() - from.toEpochMilli());
        boolean stale = window.to() == null || effTo.toEpochMilli() - window.to().readTime().toEpochMilli() > effSpan / 2;
        String status = !inSpan ? "noData" : window.value() == null || stale ? "partial" : "ok";
        double value = status.equals("ok") ? scaled(device, window.value(), identity) : 0;
        return new StatisticsRow(device.id(), device.name(), device.installAddress(), floorOf(device.installAddress()),
                value, status, sampleMap(window.from()), sampleMap(window.to()));
    }

    private static Aggregate sideAggregate(List<RowValue> counted, Map<String, List<Sample>> byMeter,
            Instant from, Instant to, Instant effTo) {
        if (counted.isEmpty()) {
            return new Aggregate(0, 0, 0, null, null, null, null, null, null, null, List.of(), List.of());
        }
        long effSpan = Math.max(HOUR_MS, effTo.toEpochMilli() - from.toEpochMilli());
        double effDays = effSpan / (double) DAY_MS;
        double total = round(counted.stream().mapToDouble(RowValue::value).sum());
        double[] hourEnergy = new double[24];
        double[] hourCovered = new double[24];
        Map<Long, Double> slotEnergy = new HashMap<>();
        Map<Long, Double> slotCovered = new HashMap<>();
        long coveredMs = 0;
        for (RowValue row : counted) {
            coveredMs += hourlyAllocation(samples(byMeter, row.id()), from.toEpochMilli(), to.toEpochMilli(),
                    hourEnergy, hourCovered, slotEnergy, slotCovered);
        }
        double coveredFraction = coveredMs / (double) (counted.size() * effSpan);
        boolean sufficient = coveredFraction >= 0.5;
        List<Object> rates = null;
        if (sufficient) {
            rates = new ArrayList<>();
            for (int hour = 0; hour < 24; hour++) {
                rates.add(hourCovered[hour] > 0 ? round(hourEnergy[hour] / (hourCovered[hour] / (double) HOUR_MS) * counted.size()) : null);
            }
        }
        List<StatisticsRow> rows = counted.stream().map(row -> new StatisticsRow(row.id(), "", "", null, row.value(), "ok", null, null)).toList();
        Double nightBase = nightAverage(rows, byMeter, from, to);
        List<DayEnergy> days = meteringDayEnergy(rows, byMeter, from, effTo);
        DayEnergy peak = days.stream().max(Comparator.comparingDouble(DayEnergy::value)).orElse(null);
        Map<String, Object> peakHour = null;
        if (sufficient && !slotEnergy.isEmpty()) {
            Map.Entry<Long, Double> top = slotEnergy.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) {
                peakHour = new LinkedHashMap<>();
                peakHour.put("value", round(top.getValue()));
                peakHour.put("sub", String.format("%02d:00", Instant.ofEpochMilli(top.getKey()).atZone(SHANGHAI).getHour()));
            }
        }
        double weekdaySum = 0;
        int weekdayCount = 0;
        for (DayEnergy day : days) {
            DayOfWeek weekday = LocalDate.parse(day.day()).getDayOfWeek();
            if (weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY) {
                weekdaySum += day.value();
                weekdayCount++;
            }
        }
        List<Map<String, Object>> dayBuckets = new ArrayList<>();
        for (ReportWindow window : bucketWindows(from, to, "day")) {
            List<Long> cells = counted.stream().map(row -> bucketCellValue(samples(byMeter, row.id()), window.from(), window.to()))
                    .filter(Objects::nonNull).toList();
            double sum = cells.stream().mapToLong(value -> value).sum() / 100.0;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from()));
            bucket.put("to", format(window.to()));
            bucket.put("value", cells.isEmpty() ? null : round(sum));
            bucket.put("partial", !window.full() || cells.size() < counted.size() || window.to().isAfter(effTo));
            bucket.put("days", Math.max(1, Math.round((Math.min(window.to().toEpochMilli(), effTo.toEpochMilli())
                    - window.from().toEpochMilli()) / (double) DAY_MS)));
            dayBuckets.add(bucket);
        }
        List<Map<String, Object>> hourBuckets = new ArrayList<>();
        for (ReportWindow window : bucketWindows(from, to, "hour")) {
            long slotStart = floorDiv(window.from().toEpochMilli() + 8 * HOUR_MS, HOUR_MS) * HOUR_MS - 8 * HOUR_MS;
            double covered = slotCovered.getOrDefault(slotStart, 0.0);
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from()));
            bucket.put("to", format(window.to()));
            bucket.put("value", covered > 0 ? round(slotEnergy.getOrDefault(slotStart, 0.0)) : null);
            bucket.put("partial", !window.full() || covered < counted.size() * (window.to().toEpochMilli() - window.from().toEpochMilli()));
            bucket.put("days", 1);
            hourBuckets.add(bucket);
        }
        return new Aggregate(total, counted.size(), coveredFraction, rates,
                round(total / effDays), round(total), round(total / effDays), nightBase,
                peak == null ? null : round(peak.value()),
                peakHour == null ? null : (Double) peakHour.get("value"),
                dayBuckets, hourBuckets);
    }

    private static List<RowValue> aRows(List<ComparisonRow> rows) {
        return rows.stream().map(row -> new RowValue(row.id(), row.a().value())).toList();
    }

    private static List<RowValue> bRows(List<ComparisonRow> rows) {
        return rows.stream().map(row -> new RowValue(row.id(), row.b().value())).toList();
    }

    private static String timeRelation(Instant from, Instant to, Instant baseFrom, Instant baseTo) {
        if (sameLocalPeriodPreviousYear(from, baseFrom) && sameLocalPeriodPreviousYear(to, baseTo)) return "yearOverYear";
        if (baseTo.equals(from)) {
            boolean months = isMonthBoundary(from) && isMonthBoundary(to) && isMonthBoundary(baseFrom) && isMonthBoundary(baseTo);
            if (months ? monthsBetween(baseFrom, baseTo) == monthsBetween(from, to)
                    : to.toEpochMilli() - from.toEpochMilli() == baseTo.toEpochMilli() - baseFrom.toEpochMilli()) {
                return "previousPeriod";
            }
        }
        return "custom";
    }

    private static boolean sameLocalPeriodPreviousYear(Instant value, Instant base) {
        LocalDateTime current = LocalDateTime.ofInstant(value, SHANGHAI);
        LocalDateTime baseline = LocalDateTime.ofInstant(base, SHANGHAI);
        return current.minusYears(1).equals(baseline);
    }

    private static boolean isMonthBoundary(Instant value) {
        LocalDateTime local = LocalDateTime.ofInstant(value, SHANGHAI);
        return local.getDayOfMonth() == 1 && local.toLocalTime().equals(LocalTime.MIDNIGHT);
    }

    private static int monthsBetween(Instant from, Instant to) {
        LocalDateTime start = LocalDateTime.ofInstant(from, SHANGHAI);
        LocalDateTime end = LocalDateTime.ofInstant(to, SHANGHAI);
        return (end.getYear() - start.getYear()) * 12 + end.getMonthValue() - start.getMonthValue();
    }

    private static List<ReportWindow> bucketWindows(Instant from, Instant to, String grain) {
        List<ReportWindow> windows = new ArrayList<>();
        Instant cursor = from;
        while (cursor.isBefore(to) && windows.size() < MAX_BUCKETS + 1) {
            Instant next;
            if ("month".equals(grain)) {
                YearMonth month = YearMonth.from(cursor.atZone(SHANGHAI));
                next = month.plusMonths(1).atDay(1).atStartOfDay(SHANGHAI).toInstant();
            } else if ("week".equals(grain)) {
                next = cursor.plus(7, ChronoUnit.DAYS);
            } else if ("hour".equals(grain)) {
                long hourStart = floorDiv(cursor.toEpochMilli() + 8 * HOUR_MS, HOUR_MS) * HOUR_MS - 8 * HOUR_MS;
                next = Instant.ofEpochMilli(hourStart + HOUR_MS);
            } else {
                next = boundary(cursor.atZone(SHANGHAI).toLocalDate().plusDays(1));
            }
            Instant end = next.isAfter(to) ? to : next;
            boolean full = "month".equals(grain)
                    ? cursor.atZone(SHANGHAI).getDayOfMonth() == 1 && cursor.atZone(SHANGHAI).toLocalTime().equals(LocalTime.MIDNIGHT) && end.equals(next)
                    : end.toEpochMilli() - cursor.toEpochMilli() == ("week".equals(grain) ? 7 * DAY_MS
                            : "hour".equals(grain) ? HOUR_MS : DAY_MS);
            windows.add(new ReportWindow(cursor, end, full));
            cursor = next;
        }
        return windows;
    }

    private static String defaultGrain(long spanMs) {
        if (spanMs <= DAY_MS) return "hour";
        if (spanMs <= 14 * DAY_MS) return "day";
        if (spanMs <= 13 * 7 * DAY_MS) return "week";
        return "month";
    }

    private static Map<String, Object> totalView(Double value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value);
        result.put("unit", "度");
        result.put("yuan", null);
        result.put("tariff", null);
        return result;
    }

    private static Map<String, Object> periodMap(PeriodFact fact) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", fact.from().atZone(SHANGHAI).toLocalDate().toString());
        result.put("requested", Map.of("from", format(fact.from()), "to", format(fact.to())));
        result.put("sampledFrom", sampleMap(fact.first()));
        result.put("sampledTo", sampleMap(fact.last()));
        result.put("usage", fact.usage());
        result.put("ratio", null);
        result.put("ratioNote", "缺少可比较的完整基期");
        result.put("status", fact.status());
        result.put("notes", fact.notes());
        return result;
    }

    private static Map<String, Object> metric(String key, Double value, String unit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", value);
        result.put("unit", unit);
        return result;
    }

    private static Map<String, Object> peerCurve(RequestIdentity identity, List<StatisticsRow> floorOk,
            Map<String, List<Sample>> byMeter, Instant from, Instant to, long effSpan) {
        if (floorOk.isEmpty()) return null;
        double[] hourEnergy = new double[24];
        double[] hourCovered = new double[24];
        long covered = 0;
        for (StatisticsRow row : floorOk) {
            covered += hourlyAllocation(samples(byMeter, row.id()), from.toEpochMilli(), to.toEpochMilli(),
                    hourEnergy, hourCovered, null, null);
        }
        if (covered / (double) (floorOk.size() * effSpan) < 0.5) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("floorPerMeterRates", null);
            return empty;
        }
        List<Object> rates = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            rates.add(hourCovered[hour] > 0 ? round(hourEnergy[hour] / (hourCovered[hour] / (double) HOUR_MS)) : null);
        }
        return Map.of("floorPerMeterRates", rates);
    }

    // ------------------------------------------------------------------
    // Policy, identity, parsing and formatting.
    // ------------------------------------------------------------------

    private boolean policyFor(RequestIdentity identity, List<AuthorizedDevice> meters) {
        if (policy == null || !identity.tenantCode().equals(policy.tenantCode()) || policy.version() == null) return false;
        return meters.stream().allMatch(meter -> {
            CompanyEnergyPolicy.Meter configured = policy.meters().get(meter.id());
            return configured != null && configured.unit() != null && configured.multiplier() != null;
        });
    }

    private double scaled(AuthorizedDevice meter, double value, RequestIdentity identity) {
        return value * policyMultiplier(identity, meter);
    }

    private Double policyMultiplier(RequestIdentity identity, AuthorizedDevice meter) {
        if (policy == null || !identity.tenantCode().equals(policy.tenantCode())) return 1.0;
        CompanyEnergyPolicy.Meter configured = policy.meters().get(meter.id());
        return configured == null || configured.multiplier() == null ? 1.0 : configured.multiplier();
    }

    private String policyUnit(RequestIdentity identity, AuthorizedDevice meter) {
        if (policy == null || !identity.tenantCode().equals(policy.tenantCode())) return null;
        CompanyEnergyPolicy.Meter configured = policy.meters().get(meter.id());
        return configured == null ? null : configured.unit();
    }

    private static ObservationResponse energyResponse(Map<String, Object> payload) {
        boolean truncated = Boolean.TRUE.equals(payload.get("truncated"));
        if (payload.get("coverage") instanceof Map<?, ?> coverage) {
            truncated = truncated || Boolean.TRUE.equals(coverage.get("truncated"));
        }
        if (!truncated) return ObservationResponse.available(payload);
        return ObservationResponse.partial(payload,
                List.of(new ObservationFailure("energy", "HISTORY_TRUNCATED",
                        "计量历史读取达到页/行预算，统计仅保留已取得事实")), false);
    }

    private static boolean isFatalEnergyError(Throwable error) {
        if (error instanceof GatewayAuthorizationException) return true;
        return error instanceof BusinessException business && business.httpStatus() < 500;
    }

    private static void validateScope(RequestIdentity identity, AuthorizedDeviceScope scope) {
        if (!identity.equals(scope.identity()) || scope.source() != IotSource.COMPANY) {
            throw new ObservationUnavailableException("公司电表授权范围与当前请求不匹配");
        }
    }

    private static void requireCompany(RequestIdentity identity) {
        if (identity.source() != IotSource.COMPANY) throw new ObservationUnavailableException("公司能耗仅支持公司来源");
    }

    private static List<AuthorizedDevice> energyMeters(AuthorizedDeviceScope scope) {
        return scope.devices().stream().filter(device -> "energy".equalsIgnoreCase(device.kind())).toList();
    }

    private static Set<String> ids(List<AuthorizedDevice> meters) {
        Set<String> result = new LinkedHashSet<>();
        for (AuthorizedDevice meter : meters) result.add(meter.id());
        return result;
    }

    private static Set<String> splitIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        String normalized = value.trim();
        if (normalized.startsWith("[")) {
            try {
                List<String> values = new ObjectMapper().readValue(normalized, new TypeReference<>() { });
                return new LinkedHashSet<>(values);
            } catch (Exception error) {
                throw new BusinessException("INVALID_ENERGY_WINDOW", "meterIds 须为 JSON 字符串数组", 400);
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : normalized.split(",")) if (!id.trim().isEmpty()) ids.add(id.trim());
        return ids;
    }

    private static boolean hasOtherScope(Map<String, String> p) {
        return p.containsKey("otherScopeLevel") || p.containsKey("otherFloor") || p.containsKey("otherMeterId")
                || p.containsKey("otherText") || p.containsKey("otherMeterIds");
    }

    private static List<Sample> samples(Map<String, List<Sample>> byMeter, String id) {
        return byMeter.getOrDefault(id, List.of());
    }

    private static Map<String, Object> sampleMap(Sample sample) {
        if (sample == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readTime", format(sample.readTime()));
        result.put("value", sample.value());
        return result;
    }

    private static String format(Instant value) {
        return value.atZone(SHANGHAI).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static Instant boundary(LocalDate date) {
        // Shanghai 08:00 is 00:00 UTC, so metering-day boundaries are UTC midnights.
        return date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant strictInstant(String value) {
        if (value == null || !value.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "起止时间须为有效的带时区时刻", 400);
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException error) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "起止时间须为有效的带时区时刻", 400);
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) throw new ObservationUnavailableException("能耗时间窗口缺失");
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value, LOCAL_DATE_TIME).toInstant(ZoneOffset.ofHours(8));
            } catch (RuntimeException error) {
                throw new ObservationUnavailableException("能耗时间格式无效");
            }
        }
    }

    private static Instant instantOrNull(String value) {
        try { return instant(value); } catch (RuntimeException invalid) { return null; }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "结束日须为 YYYY-MM-DD", 400);
        }
    }

    private static YearMonth yearMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException error) {
            throw new ObservationUnavailableException("结束月份格式无效");
        }
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

    private static int integer(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new BusinessException("INVALID_ENERGY_WINDOW", "能耗窗口参数无效", 400);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new BusinessException("INVALID_ENERGY_WINDOW", "缺少" + key, 400);
        }
        return value;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static double number(Object value) {
        try {
            double parsed = value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
            if (!Double.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("累计示数格式无效");
        }
    }

    private static Double numberOrNull(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static String floorOf(String address) {
        if (address == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+楼)").matcher(address);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static LocalDate dateOf(Instant instant) {
        return instant.atZone(SHANGHAI).toLocalDate();
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static Instant max(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static long floorDiv(long value, long divisor) {
        return Math.floorDiv(value, divisor);
    }

    private static long ceilDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static boolean isLast(Map<String, Object> payload) {
        Object value = payload.get("last");
        if (value instanceof Boolean last) return last;
        value = payload.get("hasNext");
        if (value instanceof Boolean next) return !next;
        Object nextPage = payload.get("nextPage");
        return nextPage == null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------------
    // Records.
    // ------------------------------------------------------------------

    private record Sample(String id, Instant readTime, double value) { }
    private record StreamRow(String meterId, String id, Instant readTime, Double value) { }
    private record PageData(int page, List<StreamRow> rows, boolean last, boolean error) {
        static PageData failure(int page) { return new PageData(page, List.of(), false, true); }
    }
    private record StreamResult(Map<String, List<Sample>> byMeter, Instant latest, boolean truncated) { }
    private record ReportWindow(Instant from, Instant to, boolean full) { }
    private record MonthWindow(YearMonth month, Instant from, Instant to, boolean current) { }
    private record NightWindow(Instant from, Instant to) { }
    private record PeriodFact(Instant from, Instant to, Sample first, Sample last, Double usage,
            String status, List<String> notes) { }
    private record WindowResult(Sample from, Sample to, Double value) { }
    private record DayEnergy(String day, double value) { }
    private record StatisticsRow(String id, String name, String areaName, String floor,
            double value, String status, Map<String, Object> observedFrom, Map<String, Object> observedTo) { }
    private record ComparisonRow(String id, String name, String areaName, String floor,
            StatisticsRow a, StatisticsRow b) { }
    private record RowValue(String id, double value) { }
    private record Aggregate(double total, int dataMeters, double coveredFraction, List<Object> rates,
            Double dailyAvg, Double dayTotal, Double weekdayDailyAvg, Double nightBase,
            Double peakDay, Double peakHour,
            List<Map<String, Object>> dayBuckets, List<Map<String, Object>> hourBuckets) { }
    private record Scope(String level, List<AuthorizedDevice> meters, String label, String basis) {
        Set<String> ids() {
            Set<String> ids = new LinkedHashSet<>();
            for (AuthorizedDevice meter : meters) ids.add(meter.id());
            return ids;
        }
    }
    private record RegionSide(String level, String label, String basis, List<StatisticsRow> meters,
            List<StatisticsRow> okRows, double total, int ok, double coveredFraction, List<Object> rates,
            List<Map<String, Object>> dayBuckets, List<Map<String, Object>> composition,
            Map<String, Object> context, Double dailyAvg, Double dayTotal, Double nightBase,
            Double peakDay, Double peakHour) { }
    private record NightMeter(StatisticsRow row, Double usage, String status,
            Map<String, Object> night, List<Map<String, Object>> cells) { }
    private static final class LocationSum {
        final String label;
        double value;
        final List<String> ids = new ArrayList<>();
        LocationSum(String label) { this.label = label; }
    }
    private static final class FloorSum {
        final String floor;
        double value;
        int dataMeters;
        int meters;
        FloorSum(String floor) { this.floor = floor; }
    }
}
