package ltd.qianc.iotagent.report.internal;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportUnavailableException;

/**
 * Builds report facts from server-owned evidence.
 *
 * <p>A caller selects a supported report intent and parameters only. This module
 * resolves the fixed authorized meter set, period and baselines, reads statistics
 * and comparisons through the public observation seam, and verifies that the
 * returned evidence matches the request. Model-authored blocks, totals and
 * conclusions are rejected before they can become a report fact.</p>
 */
final class ReportEvidenceService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(45);
    private static final Set<String> ENERGY_KEYS = Set.of(
            "kind", "title", "period", "comparisons", "scope", "grain", "objective");
    private static final Set<String> SAFETY_KEYS = Set.of("kind", "title", "period", "types", "objective");
    private static final Set<String> SCOPE_KEYS = Set.of("level", "floor", "text", "meterId", "meterIds");
    private static final Set<String> SAFETY_TYPES = Set.of("smoke", "gas", "flooding");

    private final IotAdapter iot;
    private final ObservationAdapter observations;
    private final Clock clock;

    ReportEvidenceService(IotAdapter iot, ObservationAdapter observations, Clock clock) {
        this.iot = Objects.requireNonNull(iot, "iot");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Map<String, Object> build(RequestIdentity identity, Map<String, Object> request) {
        if (identity == null || identity.source() != IotSource.COMPANY) {
            throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        }
        Map<String, Object> root = unwrap(request);
        String kind = text(root.get("kind"));
        if (kind == null) throw new BusinessException("INVALID_REPORT", "报告请求缺少 kind");
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "energy" -> energy(identity, root);
            case "safety" -> safety(identity, root);
            default -> throw new BusinessException("REPORT_KIND_UNSUPPORTED", "暂不支持该报告类型");
        };
    }

    private Map<String, Object> energy(RequestIdentity identity, Map<String, Object> root) {
        requireKeys(root, ENERGY_KEYS);
        String title = title(root, "用电报告");
        Map<String, Object> period = period(root.get("period"), clock.instant());
        Instant from = instant(period.get("from"));
        Instant to = instant(period.get("to"));
        List<ComparisonRequest> comparisons = comparisons(root.get("comparisons"), from, to, clock.instant());
        Scope scope = scope(identity, root.get("scope"));
        String grain = optionalText(root.get("grain"));
        if (grain != null && !List.of("day", "week", "month").contains(grain)) {
            throw new BusinessException("INVALID_REPORT", "报告粒度仅支持日/周/月");
        }

        Map<String, String> statisticsParams = new LinkedHashMap<>();
        statisticsParams.put("kind", "statistics");
        statisticsParams.put("scopeLevel", "selection");
        statisticsParams.put("meterIds", String.join(",", scope.meterIds()));
        statisticsParams.put("from", format(from));
        statisticsParams.put("to", format(to));
        if (grain != null) statisticsParams.put("grain", grain);
        Map<String, Object> statistics = query(identity, statisticsParams,
                new ExpectedSpan(from, to, null, scope.meterIds()), "statistics");

        List<Map<String, Object>> comparisonFacts = new ArrayList<>();
        List<String> comparisonLabels = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        for (ComparisonRequest comparison : comparisons) {
            comparisonLabels.add(comparison.label());
            if (comparison.unavailable() != null) {
                gaps.add(comparison.label() + "：" + comparison.unavailable());
                comparisonFacts.add(null);
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("kind", "comparison");
            params.put("axis", "time");
            params.put("scopeLevel", "selection");
            params.put("meterIds", String.join(",", scope.meterIds()));
            params.put("from", format(from));
            params.put("to", format(to));
            params.put("baseFrom", format(comparison.from()));
            params.put("baseTo", format(comparison.to()));
            comparisonFacts.add(query(identity, params,
                    new ExpectedSpan(from, to, new ExpectedSpan(comparison.from(), comparison.to(), null, scope.meterIds()), scope.meterIds()),
                    comparison.label()));
        }

        boolean statisticsIncomplete = incomplete(statistics);
        boolean statisticsHasData = dataMeters(statistics) > 0;
        if (statisticsIncomplete) gaps.add("本期统计数据不完整");
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(block("meter_report", statistics));
        for (int index = 0; index < comparisons.size(); index++) {
            ComparisonRequest comparison = comparisons.get(index);
            Map<String, Object> fact = comparisonFacts.get(index);
            if (fact == null) continue;
            Map<String, Object> diff = map(fact.get("diff"));
            if (!(diff == null ? false : diff.get("value") instanceof Number)) {
                gaps.add(comparison.label() + "不可比：两期缺少共同有效电表或基期无有效采样");
                continue;
            }
            if (incomplete(fact)) gaps.add(comparison.label() + "数据不完整");
            blocks.add(block("meter_report", fact));
        }
        if (!gaps.isEmpty()) blocks.add(textBlock("未满足或缺数的要求", String.join("；", gaps)));

        boolean complete = statisticsHasData && !statisticsIncomplete && gaps.isEmpty();
        String status = complete ? "completed" : "partial";
        String summary = summary(scope, from, to) + "；本期统计"
                + (statisticsHasData ? (statisticsIncomplete ? "已取得但不完整" : "已取得") : "未取得有效数据")
                + comparisonSummary(comparisonLabels, comparisonFacts)
                + (gaps.isEmpty() ? "。" : "；" + String.join("；", gaps) + "。");

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("meters", scope.meterIds().size());
        coverage.put("dataMeters", dataMeters(statistics));
        coverage.put("truncated", truncated(statistics));
        coverage.put("missing", gaps);
        coverage.put("statistics", map(statistics.get("coverage")));
        List<Map<String, Object>> comparisonCoverage = new ArrayList<>();
        for (int index = 0; index < comparisonFacts.size(); index++) {
            Map<String, Object> fact = comparisonFacts.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", comparisons.get(index).kind());
            item.put("coverage", fact == null ? null : fact.get("coverage"));
            item.put("comparable", fact != null && map(fact.get("diff")) != null
                    && map(fact.get("diff")).get("value") instanceof Number);
            comparisonCoverage.add(item);
        }
        coverage.put("comparisons", comparisonCoverage);

        List<Map<String, Object>> requirements = new ArrayList<>();
        Map<String, Object> statisticsRequirement = new LinkedHashMap<>();
        statisticsRequirement.put("kind", "statistics");
        statisticsRequirement.put("from", format(from));
        statisticsRequirement.put("to", format(to));
        requirements.add(statisticsRequirement);
        for (ComparisonRequest comparison : comparisons) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", comparison.kind());
            item.put("from", format(from));
            item.put("to", format(to));
            item.put("baseFrom", comparison.from() == null ? null : format(comparison.from()));
            item.put("baseTo", comparison.to() == null ? null : format(comparison.to()));
            item.put("unavailable", comparison.unavailable());
            requirements.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("objective", optionalText(root.get("objective")));
        result.put("status", status);
        result.put("summary", summary);
        result.put("blocks", blocks);
        Map<String, Object> requirementsValue = new LinkedHashMap<>();
        requirementsValue.put("period", period);
        requirementsValue.put("comparisons", comparisons.stream().map(ComparisonRequest::toMap).toList());
        requirementsValue.put("meterIds", scope.meterIds());
        requirementsValue.put("grain", grain);
        result.put("requirements", requirementsValue);
        result.put("coverage", coverage);
        result.put("meterIds", scope.meterIds());
        result.put("authorizationMeterIds", scope.allMeterIds());
        result.put("build", buildId());
        return result;
    }

    private Map<String, Object> safety(RequestIdentity identity, Map<String, Object> root) {
        requireKeys(root, SAFETY_KEYS);
        String title = title(root, "安全告警报告");
        Map<String, Object> period = period(root.get("period"), clock.instant());
        Instant from = instant(period.get("from"));
        Instant to = instant(period.get("to"));
        List<String> types = new ArrayList<>();
        Object rawTypes = root.get("types");
        if (rawTypes == null) {
            types.addAll(List.of("smoke", "gas", "flooding"));
        } else {
            for (Object value : list(rawTypes)) {
                String type = text(value);
                if (type == null || !SAFETY_TYPES.contains(type)) {
                    throw new BusinessException("INVALID_REPORT", "安全台账类型仅支持 smoke/gas/flooding");
                }
                if (!types.contains(type)) types.add(type);
            }
            if (types.isEmpty()) throw new BusinessException("INVALID_REPORT", "安全报告至少需要一类台账");
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<Map<String, Object>> coverages = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int totalEvents = 0;
        boolean complete = true;
        for (String type : types) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("type", type);
            params.put("from", format(from));
            params.put("to", format(to));
            params.put("limit", "100");
            try {
                ObservationResponse response = observations.read(new ObservationRequest(
                        identity, ObservationOperation.SAFETY_EVENTS, params)).block(CALL_TIMEOUT);
                if (response == null || response.status() == ObservationStatus.UNAVAILABLE) {
                    gaps.add(type + "台账读取不可用");
                    complete = false;
                    continue;
                }
                Map<String, Object> payload = response.payload();
                List<Map<String, Object>> items = maps(payload.get("items"));
                Map<String, Object> coverage = new LinkedHashMap<>();
                coverage.put("type", type);
                coverage.put("events", items.size());
                coverage.put("total", payload.get("total"));
                coverage.put("scanned", payload.get("scanned"));
                coverage.put("complete", !Boolean.FALSE.equals(payload.get("coverageComplete")));
                if (!Boolean.TRUE.equals(payload.get("coverageComplete"))) {
                    // null means the adapter could not establish coverage; do not claim complete.
                    coverage.put("complete", false);
                    complete = false;
                    gaps.add(type + "台账覆盖不完整");
                }
                coverages.add(coverage);
                totalEvents += items.size();
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "safety_ledger");
                block.put("ledger", type);
                block.put("events", items);
                block.put("coverage", coverage);
                blocks.add(block);
            } catch (RuntimeException error) {
                complete = false;
                String message = error instanceof BusinessException business
                        ? business.getMessage() : "读取失败";
                gaps.add(type + "台账：" + message);
            }
        }
        if (!gaps.isEmpty()) blocks.add(textBlock("未取得或未覆盖的台账要求", String.join("；", gaps)));
        String summary = "报告期间 " + format(from) + " 至 " + format(to) + "（上海时间），"
                + types.size() + " 类安全台账共取得 " + totalEvents + " 条记录"
                + (gaps.isEmpty() ? "。" : "；" + String.join("；", gaps) + "。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("objective", optionalText(root.get("objective")));
        result.put("status", complete ? "completed" : "partial");
        result.put("summary", summary);
        result.put("blocks", blocks);
        result.put("requirements", Map.of("period", period, "types", types));
        result.put("coverage", Map.of("types", coverages, "missing", gaps, "events", totalEvents));
        result.put("meterIds", List.of());
        result.put("authorizationMeterIds", List.of());
        result.put("build", buildId());
        return result;
    }

    private Map<String, Object> query(RequestIdentity identity, Map<String, String> parameters,
            ExpectedSpan expected, String label) {
        ObservationResponse response;
        try {
            response = observations.read(new ObservationRequest(identity, ObservationOperation.COMPANY_METER_REPORT, parameters))
                    .block(CALL_TIMEOUT);
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_UNAVAILABLE", label + "取证暂不可用");
        }
        if (response == null || response.status() == ObservationStatus.UNAVAILABLE) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_UNAVAILABLE", label + "取证暂不可用");
        }
        Map<String, Object> payload = new LinkedHashMap<>(response.payload());
        if (!"meter-report".equals(text(payload.get("type")))) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回事实类型不符");
        }
        Map<String, Object> params = map(payload.get("params"));
        Map<String, Object> span = params == null ? null : map(params.get("span"));
        Map<String, Object> requested = span == null ? null : map(span.get("requested"));
        if (requested == null || !sameInstant(requested.get("from"), expected.from())
                || !sameInstant(requested.get("to"), expected.to())) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回期间与请求不符");
        }
        Map<String, Object> scope = params == null ? null : map(params.get("scope"));
        if (scope == null || !"selection".equals(text(scope.get("level")))
                || !(scope.get("meterCount") instanceof Number count)
                || count.intValue() != expected.meterIds().size()) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回范围与请求不符");
        }
        List<String> authorization = strings(payload.get("authorizationMeterIds"));
        if (!new LinkedHashSet<>(authorization).containsAll(expected.meterIds())) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回授权范围与请求不符");
        }
        if (expected.baseline() == null && !"statistics".equals(text(payload.get("kind")))) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回统计类型不符");
        }
        if (expected.baseline() != null) {
            if (!"comparison".equals(text(payload.get("kind"))) || !"time".equals(text(params.get("axis")))) {
                throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回对比轴不符");
            }
            Map<String, Object> baseline = map(params.get("baseline"));
            Map<String, Object> baselineRequested = baseline == null ? null : map(baseline.get("requested"));
            if (baselineRequested == null || !sameInstant(baselineRequested.get("from"), expected.baseline().from())
                    || !sameInstant(baselineRequested.get("to"), expected.baseline().to())) {
                throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "返回基期与请求不符");
            }
        }
        Map<String, Object> coverage = map(payload.get("coverage"));
        if (coverage == null) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_MISMATCH", label + "缺少覆盖事实");
        }
        return payload;
    }

    private Scope scope(RequestIdentity identity, Object raw) {
        AuthorizedDeviceScope authorized = loadScope(identity);
        List<AuthorizedDevice> all = authorized.devices().stream()
                .filter(device -> "energy".equalsIgnoreCase(device.kind())).toList();
        List<String> allIds = all.stream().map(AuthorizedDevice::id).toList();
        Map<String, Object> request;
        if (raw == null) {
            request = new LinkedHashMap<>();
            request.put("level", "all");
        } else {
            request = map(raw);
        }
        if (request == null) throw new BusinessException("INVALID_REPORT", "报告范围无效");
        for (String key : request.keySet()) {
            if (!SCOPE_KEYS.contains(key)) throw new BusinessException("INVALID_REPORT", "报告范围包含未知字段");
        }
        String level = text(request.get("level"));
        if (level == null) level = "all";
        List<AuthorizedDevice> selected;
        switch (level) {
            case "all" -> selected = all;
            case "floor" -> {
                String floor = text(request.get("floor"));
                if (floor == null) throw new BusinessException("INVALID_REPORT", "楼层报告范围缺少楼层");
                selected = all.stream().filter(device -> floor.equals(floorOf(device.installAddress()))).toList();
            }
            case "location" -> {
                String id = text(request.get("meterId"));
                AuthorizedDevice device = id == null ? null : all.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
                if (device == null) throw new BusinessException("REPORT_SCOPE_CHANGED", "报告引用的电表不在当前授权范围内", 404);
                selected = List.of(device);
            }
            case "nameMatch" -> {
                String text = text(request.get("text"));
                if (text == null) throw new BusinessException("INVALID_REPORT", "名称匹配范围缺少文本");
                String floor = text(request.get("floor"));
                selected = all.stream().filter(device -> (floor == null || floor.equals(floorOf(device.installAddress())))
                        && (device.installAddress() + " " + device.name()).contains(text)).toList();
            }
            case "selection" -> {
                List<String> ids = strings(request.get("meterIds"));
                if (ids.isEmpty()) throw new BusinessException("INVALID_REPORT", "选择范围缺少电表");
                LinkedHashSet<String> unique = new LinkedHashSet<>(ids);
                selected = unique.stream().map(id -> all.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null)).toList();
                if (selected.stream().anyMatch(Objects::isNull)) {
                    throw new BusinessException("REPORT_SCOPE_CHANGED", "报告引用的电表不在当前授权范围内", 404);
                }
            }
            default -> throw new BusinessException("INVALID_REPORT", "报告范围层级无效");
        }
        if (selected.isEmpty()) throw new BusinessException("INVALID_REPORT", "报告范围没有授权电表");
        List<String> meterIds = selected.stream().map(AuthorizedDevice::id).toList();
        return new Scope(level, meterIds, allIds);
    }

    private AuthorizedDeviceScope loadScope(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(CALL_TIMEOUT);
            if (scope == null || scope.source() != IotSource.COMPANY || !identity.equals(scope.identity())) {
                throw new ReportUnavailableException("REPORT_SCOPE_UNAVAILABLE", "授权目录暂不可用，请稍后重试");
            }
            return scope;
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_SCOPE_UNAVAILABLE", "授权目录暂不可用，请稍后重试");
        }
    }

    private List<ComparisonRequest> comparisons(Object raw, Instant from, Instant to, Instant now) {
        List<ComparisonRequest> result = new ArrayList<>();
        if (raw == null) return result;
        List<Object> values = list(raw);
        if (values.size() > 2) throw new BusinessException("INVALID_REPORT", "报告对比最多支持两期");
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : values) {
            String kind = text(value);
            if (kind == null || !List.of("previousPeriod", "yearOverYear").contains(kind)) {
                throw new BusinessException("INVALID_REPORT", "报告对比类型无效");
            }
            if (!seen.add(kind)) throw new BusinessException("INVALID_REPORT", "报告对比类型不得重复");
            String label = "yearOverYear".equals(kind) ? "同比" : "环比";
            Instant baseFrom, baseTo;
            if ("yearOverYear".equals(kind)) {
                baseFrom = previousYear(from);
                baseTo = previousYear(to);
            } else {
                baseFrom = previousPeriodStart(from, to);
                baseTo = from;
            }
            String unavailable = null;
            if (!baseFrom.isBefore(baseTo) || baseTo.isAfter(now)) {
                unavailable = "该期间无法形成有效的比较基期，请明确比较期间";
            }
            result.add(new ComparisonRequest(kind, label, baseFrom, baseTo, unavailable));
        }
        return result;
    }

    private static Instant previousYear(Instant value) {
        LocalDateTime local = LocalDateTime.ofInstant(value, SHANGHAI).minusYears(1);
        return local.atZone(SHANGHAI).toInstant();
    }

    private static Instant previousPeriodStart(Instant from, Instant to) {
        LocalDateTime start = LocalDateTime.ofInstant(from, SHANGHAI);
        LocalDateTime end = LocalDateTime.ofInstant(to, SHANGHAI);
        if (isMeteringMonthBoundary(start) && isMeteringMonthBoundary(end)) {
            int months = (end.getYear() - start.getYear()) * 12 + end.getMonthValue() - start.getMonthValue();
            return start.minusMonths(months).atZone(SHANGHAI).toInstant();
        }
        return from.minusMillis(to.toEpochMilli() - from.toEpochMilli());
    }

    private static boolean isMeteringMonthBoundary(LocalDateTime value) {
        return value.getDayOfMonth() == 1 && value.toLocalTime().equals(LocalTime.of(8, 0));
    }

    private Map<String, Object> period(Object raw, Instant now) {
        Map<String, Object> request = map(raw);
        if (request == null) throw new BusinessException("INVALID_REPORT", "报告期间不能为空");
        String kind = text(request.get("kind"));
        if ("absolute".equals(kind)) {
            Instant from = strictInstant(request.get("from"));
            Instant to = strictInstant(request.get("to"));
            if (!from.isBefore(to) || to.isAfter(now)) {
                throw new BusinessException("INVALID_REPORT", "报告期间须已发生且结束晚于开始");
            }
            return Map.of("kind", "absolute", "from", format(from), "to", format(to));
        }
        if ("previous".equals(kind)) {
            String unit = text(request.get("unit"));
            if (unit == null || !List.of("day", "week", "month").contains(unit)) {
                throw new BusinessException("INVALID_REPORT", "报告期间单位仅支持日/周/月");
            }
            LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
            Instant to = switch (unit) {
                case "day" -> boundary(today);
                case "week" -> boundary(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                default -> boundary(today.withDayOfMonth(1));
            };
            Instant from = switch (unit) {
                case "day" -> to.minusMillis(86_400_000L);
                case "week" -> to.minusMillis(7 * 86_400_000L);
                default -> boundary(today.withDayOfMonth(1).minusMonths(1));
            };
            if (to.isAfter(now)) throw new BusinessException("INVALID_REPORT", "报告期间须已发生");
            return Map.of("kind", "previous", "unit", unit, "from", format(from), "to", format(to));
        }
        throw new BusinessException("INVALID_REPORT", "报告期间类型无效");
    }

    private static Instant boundary(LocalDate date) {
        // Shanghai 08:00 is 00:00 UTC; report periods use the metering boundary.
        return date.atStartOfDay(ZoneId.of("UTC")).toInstant();
    }

    private static Instant strictInstant(Object value) {
        String text = text(value);
        if (text == null || !text.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$")) {
            throw new BusinessException("INVALID_REPORT", "报告期间须为有效的带时区时刻");
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException error) {
            throw new BusinessException("INVALID_REPORT", "报告期间须为有效的带时区时刻");
        }
    }

    private static Instant instant(Object value) {
        return strictInstant(value);
    }

    private static boolean sameInstant(Object left, Instant right) {
        String value = text(left);
        if (value == null || right == null) return false;
        try {
            return OffsetDateTime.parse(value).toInstant().equals(right);
        } catch (DateTimeParseException error) {
            return false;
        }
    }

    private static String format(Instant value) {
        return value.atZone(SHANGHAI).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String summary(Scope scope, Instant from, Instant to) {
        return "报告期间 " + format(from) + " 至 " + format(to) + "（上海时间），固定计量范围 "
                + scope.meterIds().size() + " 台";
    }

    private static String comparisonSummary(List<String> labels, List<Map<String, Object>> facts) {
        if (labels.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            Map<String, Object> fact = facts.get(index);
            boolean comparable = fact != null && map(fact.get("diff")) != null
                    && map(fact.get("diff")).get("value") instanceof Number;
            parts.add(labels.get(index) + "：" + (comparable ? "可比" : "未取得可比较事实"));
        }
        return "；" + String.join("；", parts);
    }

    private static boolean incomplete(Map<String, Object> fact) {
        Map<String, Object> coverage = map(fact.get("coverage"));
        if (coverage == null) return true;
        if (Boolean.TRUE.equals(coverage.get("truncated"))) return true;
        if (!list(coverage.get("noData")).isEmpty()) return true;
        if (!list(coverage.get("partial")).isEmpty()) return true;
        Map<String, Object> params = map(fact.get("params"));
        Map<String, Object> diff = map(fact.get("diff"));
        if (diff != null && params != null && "time".equals(text(params.get("axis")))) {
            return !(diff.get("value") instanceof Number);
        }
        return false;
    }

    private static int dataMeters(Map<String, Object> fact) {
        Map<String, Object> coverage = map(fact.get("coverage"));
        return coverage != null && coverage.get("dataMeters") instanceof Number number
                ? number.intValue() : 0;
    }

    private static boolean truncated(Map<String, Object> fact) {
        Map<String, Object> coverage = map(fact.get("coverage"));
        return coverage != null && Boolean.TRUE.equals(coverage.get("truncated"));
    }

    private static Map<String, Object> block(String type, Map<String, Object> report) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("report", report);
        return block;
    }

    private static Map<String, Object> textBlock(String title, String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("title", title);
        block.put("text", text);
        return block;
    }

    private static String title(Map<String, Object> root, String fallback) {
        String value = optionalText(root.get("title"));
        if (value == null) return fallback;
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static void requireKeys(Map<String, Object> root, Set<String> allowed) {
        for (String key : root.keySet()) {
            if (!allowed.contains(key)) {
                throw new BusinessException("INVALID_REPORT", "报告请求包含未知字段：" + key);
            }
        }
    }

    private static Map<String, Object> unwrap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) throw new BusinessException("INVALID_REPORT", "报告请求不能为空");
        if (raw.get("report") instanceof Map<?, ?> report) {
            Map<String, Object> nested = map(report);
            if (nested == null || !nested.containsKey("kind")) {
                throw new BusinessException("INVALID_REPORT", "报告内容必须由服务端取证，不接受模型编写的报告区块");
            }
            return nested;
        }
        return raw;
    }

    private static String buildId() {
        String value = System.getenv("AGENT_BUILD_ID");
        return value == null || value.isBlank() ? "local" : value.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Object> list(Object value) {
        if (value instanceof List<?> values) return new ArrayList<>(values);
        return List.of();
    }

    private static List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(value)) {
            Map<String, Object> map = map(item);
            if (map != null) result.add(map);
        }
        return result;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) {
            String text = text(item);
            if (text != null) result.add(text);
        }
        return result;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String optionalText(Object value) {
        return text(value);
    }

    private static String floorOf(String address) {
        if (address == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+楼)").matcher(address);
        return matcher.find() ? matcher.group(1) : null;
    }

    private record Scope(String level, List<String> meterIds, List<String> allMeterIds) { }

    private record ExpectedSpan(Instant from, Instant to, ExpectedSpan baseline, List<String> meterIds) { }

    private record ComparisonRequest(String kind, String label, Instant from, Instant to, String unavailable) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", kind);
            result.put("from", from == null ? null : format(from));
            result.put("to", to == null ? null : format(to));
            result.put("unavailable", unavailable);
            return result;
        }
    }
}
