package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Public package-root contract tests for the local Company energy seam. */
class CompanyEnergyObservationComposerContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT", IotSource.COMPANY);
    private static final AuthorizedDevice METER =
            new AuthorizedDevice("meter-1", "energy", "一号表", "26楼办公室", true);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void dailyAggregatesShanghaiEightHourBucketsWithoutZeroFilling() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                pageNumber == 0 ? page(List.of(row("1", "2026-10-02 08:00:00", 100), row("2", "2026-10-03 08:00:00", 125)), true) : empty())));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();

        List<?> days = (List<?>) payload.get("days");
        Map<?, ?> day = (Map<?, ?>) days.get(0);
        assertThat(day.get("usage")).isEqualTo(25.0d);
        assertThat(day.get("complete")).isEqualTo(true);
        assertThat(payload.get("timezone")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void missingAndStaleSamplesRemainNullAndNotComputable() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of())));
        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();
        Map<?, ?> missingDay = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        Map<?, ?> missingMeter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        assertThat(missingDay.get("usage")).isNull();
        assertThat(((Map<?, ?>) ((Map<?, ?>) missingMeter.get("daily")).get("2026-10-03")).get("status"))
                .isEqualTo("not_computable");
        assertThat(payload.get("truncated")).isEqualTo(false);
        CompanyEnergyObservationComposer stale = composer((request, page) -> Mono.just(ObservationResponse.available(
                page(List.of(row("old", "2026-09-01 08:00:00", 100)), true))));
        Map<String, Object> stalePayload = stale.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();
        assertThat(((Map<?, ?>) ((List<?>) stalePayload.get("days")).get(0)).get("usage")).isNull();
    }

    @Test
    void boundedPageBudgetMarksPartialInsteadOfInventingAValue() {
        AtomicInteger pages = new AtomicInteger();
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> {
                    pages.incrementAndGet();
                    return Mono.just(ObservationResponse.available(page(List.of(
                            row("1", "2026-10-02 08:00:00", 100)), false)));
                }), iot(COMPANY, List.of(METER)), CLOCK, 1, 1);
        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_METER_REPORT, Map.of("kind", "statistics", "scopeLevel", "location",
                        "meterId", "meter-1", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();

        assertThat(pages.get()).isEqualTo(1);
        assertThat(((Map<?, ?>) payload.get("coverage")).get("truncated")).isEqualTo(true);
        assertThat(((Map<?, ?>) payload.get("summary")).get("partial")).isEqualTo(true);
    }

    @Test
    void unauthorizedCurrentScopeFailsClosed() {
        RequestIdentity other = new RequestIdentity("Bearer other", "TENANT", IotSource.COMPANY);
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of()))), iot(other, List.of(METER)), CLOCK);

        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void selectedMeterOutsideCurrentScopeIsForbidden() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of())));
        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "selection", "meterIds", "meter-outside",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block())
                .isInstanceOfSatisfying(ltd.qianc.iotagent.error.BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(403));
    }

    @Test
    void malformedSampleFailsClosed() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                page(List.of(row("1", "not-a-time", 100)), true))));

        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void statisticsSupportsAllAndSingleMeterTimeWindows() {
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
        ObservationAdapter raw = raw((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                row(request.parameters().get("equipmentId") + "-1", "2026-10-02 08:00:00", 10),
                row(request.parameters().get("equipmentId") + "-2", "2026-10-03 08:00:00", 15)), true))));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(raw,
                iot(COMPANY, List.of(METER, second)), CLOCK);

        Map<String, Object> all = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        Map<String, Object> one = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "location", "meterId", "meter-1", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) all.get("params")).get("scope").toString()).contains("meterCount=2");
        assertThat(((Map<?, ?>) one.get("params")).get("scope").toString()).contains("meterCount=1");
        assertThat(((Map<?, ?>) one.get("total")).get("value")).isEqualTo(5.0d);
    }

    @Test
    void comparisonKeepsAbsoluteDiffAndExplainsZeroBaseline() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                row("a", "2026-10-01 08:00:00", 100), row("b", "2026-10-02 08:00:00", 100), row("c", "2026-10-03 08:00:00", 110)), true))));
        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "scopeLevel", "location", "meterId", "meter-1",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00",
                        "baseFrom", "2026-10-01T08:00:00+08:00", "baseTo", "2026-10-02T08:00:00+08:00"))).block().payload();
        Map<?, ?> diff = (Map<?, ?>) payload.get("diff");
        assertThat(diff.get("value")).isEqualTo(10.0d);
        assertThat(diff.get("pct")).isNull();
        assertThat(diff.get("pctNote")).isEqualTo("基期为零，比例不可计算");
    }

    @Test
    void weeklyComparisonKeepsZeroBaselineUndefinedAndCurrentWeekSeparate() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                page(List.of(row("a", "2026-09-21 08:00:00", 100),
                        row("b", "2026-09-28 08:00:00", 100),
                        row("c", "2026-10-05 08:00:00", 110)), true))));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.ENERGY_COMPARISON,
                Map.of("equipmentId", "meter-1", "weeks", "2", "endDate", "2026-10-05",
                        "boundaryHour", "8"))).block().payload();

        assertThat(payload.get("type")).isEqualTo("energy-comparison");
        assertThat(((List<?>) payload.get("weeks"))).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) ((List<?>) payload.get("weeks")).get(0);
        assertThat(first.get("usage")).isEqualTo(10.0d);
        assertThat(first.get("ratio")).isNull();
        assertThat(first.get("ratioNote")).isEqualTo("基期为零，比例不可计算");
        assertThat(payload.get("current")).isNotNull();
        assertThat(((Map<?, ?>) payload.get("basis")).get("unit")).isNull();
    }

    @Test
    void monthlyCompanyReportRetainsPerMeterFactsButDoesNotInventPendingAggregate() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                page(List.of(row("a", "2026-07-01 08:00:00", 100),
                        row("b", "2026-08-01 08:00:00", 110),
                        row("c", "2026-09-01 08:00:00", 130),
                        row("d", "2026-10-01 08:00:00", 160)), true))));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_REPORT,
                Map.of("months", "2", "endMonth", "2026-09", "boundaryHour", "8"))).block().payload();

        assertThat(payload.get("type")).isEqualTo("company-energy");
        assertThat(((Map<?, ?>) payload.get("aggregation")).get("status")).isEqualTo("pending");
        assertThat(((Map<?, ?>) payload.get("aggregation")).get("unit")).isNull();
        List<?> periods = (List<?>) payload.get("periods");
        assertThat(((Map<?, ?>) periods.get(0)).get("usage")).isNull();
        assertThat(((Map<?, ?>) periods.get(1)).get("usage")).isNull();
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        List<?> meterPeriods = (List<?>) meter.get("periods");
        assertThat(((Map<?, ?>) meterPeriods.get(0)).get("usage")).isEqualTo(20.0d);
        assertThat(((Map<?, ?>) meterPeriods.get(1)).get("usage")).isEqualTo(30.0d);
    }

    @Test
    void companyDailyReadsPagesByWindowNotByMeterCount() {
        AtomicInteger calls = new AtomicInteger();
        List<AuthorizedDevice> meters = List.of(METER,
                new AuthorizedDevice("meter-2", "energy", "二号表", "27楼", true),
                new AuthorizedDevice("meter-3", "energy", "三号表", "28楼", true));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> {
                    calls.incrementAndGet();
                    int size = Integer.parseInt(request.parameters().getOrDefault("size", "20"));
                    List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, size)
                            .mapToObj(index -> {
                                Map<String, Object> row = new java.util.LinkedHashMap<>();
                                row.put("id", "r" + pageNumber + "-" + index);
                                row.put("equipmentId", meters.get(index % meters.size()).id());
                                row.put("readTime", "2026-10-01 08:00:00");
                                row.put("epf", 100 + index);
                                return row;
                            }).toList();
                    return Mono.just(ObservationResponse.available(page(rows, false)));
                }),
                iot(COMPANY, meters), CLOCK);

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "30"))).block();

        assertThat(response).isNotNull();
        // Page count follows the window, so adding meters must not add upstream round trips.
        assertThat(calls.get()).isLessThanOrEqualTo(10);
        assertThat(((Map<?, ?>) response.payload().get("budget")).get("truncated")).isEqualTo(false);
    }

    private static CompanyEnergyObservationComposer composer(PageFixture fixture) {
        return CompanyEnergyObservationComposerFactory.composed(raw(fixture), iot(COMPANY, List.of(METER)), CLOCK);
    }

    private static ObservationAdapter raw(PageFixture fixture) {
        return request -> fixture.read(request, Integer.parseInt(request.parameters().getOrDefault("page", "0")));
    }

    private static IotAdapter iot(RequestIdentity identity, List<AuthorizedDevice> devices) {
        return request -> Mono.just(new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return identity; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return devices; }
        });
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, boolean last) {
        return Map.of("content", rows, "last", last);
    }

    private static Map<String, Object> row(String id, String readTime, double epf) {
        return Map.of("id", id, "equipmentId", "meter-1", "readTime", readTime, "epf", epf);
    }

    private static Map<String, Object> empty() { return Map.of(); }

    @FunctionalInterface
    private interface PageFixture { Mono<ObservationResponse> read(ObservationRequest request, int page); }
}
