package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Public package-root contract tests for the local Company energy seam. */
class CompanyEnergyObservationComposerContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT", IotSource.COMPANY);
    private static final AuthorizedDevice METER =
            new AuthorizedDevice("meter-1", "energy", "一号表", "26楼办公室", true);
    private static final AuthorizedDevice SECOND =
            new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void dailyUsesShanghaiEightHourMeteringDays() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("1", "2026-10-02 08:00:00", "meter-1", 100),
                row("2", "2026-10-03 08:00:00", "meter-1", 125)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-02"))).block().payload();

        Map<?, ?> day = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        assertThat(day.get("date")).isEqualTo("2026-10-02");
        assertThat(day.get("usage")).isEqualTo(25.0d);
        assertThat(day.get("complete")).isEqualTo(true);
        assertThat(((Map<?, ?>) payload.get("basis")).get("boundaryHour")).isEqualTo(8);
        assertThat(payload.get("timezone")).isEqualTo("Asia/Shanghai");
    }



    @Test
    void energyComposerUsesTheEnergyOnlyAuthorizationScope() {
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> Mono.just(ObservationResponse.available(pageBody(List.of(
                        row("a", "2026-10-02 08:00:00", "meter-1", 100)), true)))),
                new IotAdapter() {
                    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
                        return Mono.just(AuthorizedDeviceScope.of(request, IotSource.COMPANY, List.of()));
                    }

                    @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity request) {
                        return Mono.just(AuthorizedDeviceScope.of(request, IotSource.COMPANY, List.of(METER)));
                    }
                }, CLOCK);
        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1"))).block().payload();

        assertThat((List<?>) payload.get("meters")).hasSize(1);
        assertThat(payload.get("latestSampleTime")).isNotNull();
    }

    @Test
    void dailyDoesNotCountPartialBoundaryUsageAsComplete() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-10-02 08:00:00", "meter-1", 100),
                row("b", "2026-10-03 07:59:00", "meter-1", 125)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-02"))).block().payload();

        Map<?, ?> day = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        assertThat(day.get("complete")).isEqualTo(false);
        assertThat(day.get("usage")).isNull();
        assertThat(day.get("coveredMeters")).isEqualTo(0);
        assertThat(day.get("partialMeters")).isEqualTo(1);
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        Map<?, ?> cell = (Map<?, ?>) ((Map<?, ?>) meter.get("daily")).get("2026-10-02");
        assertThat(cell.get("status")).isEqualTo("partial");
    }

    @Test
    void regularTwelveHourSamplesProduceMeterCardForEqualLengthObservedWeek() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-09-30 20:00:00", "meter-1", 100),
                row("b", "2026-10-07 20:00:00", "meter-1", 125)), true));
        Map<String, Object> report = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_METER_REPORT, Map.of("kind", "statistics", "scopeLevel", "all",
                        "from", "2026-10-01T00:00:00+08:00", "to", "2026-10-08T00:00:00+08:00"))).block().payload();

        assertThat(((Number) ((Map<?, ?>) report.get("coverage")).get("dataMeters")).intValue()).isEqualTo(1);
        assertThat(((Map<?, ?>) report.get("total")).get("value")).isEqualTo(25.0d);
        Map<?, ?> span = (Map<?, ?>) ((Map<?, ?>) report.get("params")).get("span");
        Map<?, ?> observed = (Map<?, ?>) span.get("observed");
        assertThat(observed.get("from")).isEqualTo("2026-09-30T20:00:00+08:00");
        assertThat(observed.get("to")).isEqualTo("2026-10-07T20:00:00+08:00");
    }

    @Test
    void statisticsRejectsSingleSampleAndResetFromTheTotal() {
        CompanyEnergyObservationComposer interior = composer(page(List.of(
                row("a", "2026-10-02 09:00:00", "meter-1", 100)), true));
        ObservationResponse interiorResponse = interior.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_METER_REPORT, Map.of("kind", "statistics", "scopeLevel", "all",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block();
        assertThat(((Map<?, ?>) interiorResponse.payload().get("total")).get("value")).isNull();
        assertThat((List<?>) ((Map<?, ?>) interiorResponse.payload().get("coverage")).get("partial")).hasSize(1);

        CompanyEnergyObservationComposer shortened = composer(page(List.of(
                row("a", "2026-10-02 08:00:00", "meter-1", 100),
                row("b", "2026-10-03 07:00:00", "meter-1", 90)), true));
        ObservationResponse shortenedResponse = shortened.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_METER_REPORT, Map.of("kind", "statistics", "scopeLevel", "all",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block();
        assertThat(((Map<?, ?>) shortenedResponse.payload().get("total")).get("value")).isNull();
        assertThat((List<?>) ((Map<?, ?>) shortenedResponse.payload().get("coverage")).get("partial")).hasSize(1);
    }

    @Test
    void nightWindowWithMissingEightBoundaryIsNotTreatedAsMeasured() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-10-01 20:00:00", "meter-1", 100),
                row("b", "2026-10-02 07:00:00", "meter-1", 112)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "windowFrom", "20:00", "windowTo", "08:00",
                        "from", "2026-10-01T08:00:00+08:00", "to", "2026-10-02T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) payload.get("total")).get("value")).isNull();
        assertThat(((Map<?, ?>) payload.get("windowSummary")).get("validMeterNights")).isEqualTo(0);
        assertThat((List<?>) ((Map<?, ?>) payload.get("coverage")).get("partial")).hasSize(1);
    }


    @Test
    void defaultDailyAnchorsLatestCompleteMeteringDayAndReadsOnlyRequestedDays() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-10-01 08:00:00", "meter-1", 100),
                row("b", "2026-10-02 08:00:00", "meter-1", 125)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1"))).block().payload();

        assertThat((List<?>) payload.get("days")).hasSize(1);
        Map<?, ?> day = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        assertThat(day.get("date")).isEqualTo("2026-10-01");
        assertThat(day.get("usage")).isEqualTo(25.0d);
    }

    @Test
    void defaultDailyWindowIsThirtyMeteringDaysAndMissingSamplesStayNull() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of())).block().payload();

        assertThat((List<?>) payload.get("days")).hasSize(30);
        Map<?, ?> missingDay = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        assertThat(missingDay.get("usage")).isNull();
        assertThat(missingDay.get("complete")).isEqualTo(false);
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        Map<?, ?> cell = (Map<?, ?>) ((Map<?, ?>) meter.get("daily")).values().iterator().next();
        assertThat(cell.get("usage")).isNull();
        assertThat(cell.get("status")).isEqualTo("not_computable");
        assertThat(payload.get("truncated")).isEqualTo(false);
    }

    @Test
    void boundedPageBudgetMarksPartialInsteadOfInventingAValue() {
        AtomicInteger pages = new AtomicInteger();
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> {
                    pages.incrementAndGet();
                    return Mono.just(ObservationResponse.available(pageBody(List.of(
                            row("1", "2026-10-02 08:00:00", "meter-1", 100)), false)));
                }), iot(COMPANY, List.of(METER)), CLOCK, 1, 1000);
        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_METER_REPORT, Map.of("kind", "statistics", "scopeLevel", "location",
                        "meterId", "meter-1", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block();

        assertThat(pages.get()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(((Map<?, ?>) response.payload().get("coverage")).get("truncated")).isEqualTo(true);
        assertThat(((Map<?, ?>) response.payload().get("total")).get("value")).isNull();
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
        CompanyEnergyObservationComposer composer = composer(page(List.of(), true));
        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "selection", "meterIds", "meter-outside",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block())
                .isInstanceOfSatisfying(ltd.qianc.iotagent.error.BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(403));
    }

    @Test
    void malformedSampleFailsClosed() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("1", "not-a-time", "meter-1", 100)), true));

        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block())
                .isInstanceOf(ltd.qianc.iotagent.error.BusinessException.class);
    }

    @Test
    void statisticsSupportsAllAndSingleMeterScopesWithOneCalculation() {
        ObservationAdapter raw = raw((request, pageNumber) -> Mono.just(ObservationResponse.available(pageBody(List.of(
                row("a", "2026-10-02 08:00:00", "meter-1", 10),
                row("b", "2026-10-03 08:00:00", "meter-1", 15),
                row("c", "2026-10-02 08:00:00", "meter-2", 10),
                row("d", "2026-10-03 08:00:00", "meter-2", 15)), true))));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER, SECOND)), CLOCK);

        Map<String, Object> all = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        Map<String, Object> one = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "location", "meterId", "meter-1",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) ((Map<?, ?>) all.get("params")).get("scope")).get("meterCount")).isEqualTo(2);
        assertThat(((Map<?, ?>) ((Map<?, ?>) one.get("params")).get("scope")).get("meterCount")).isEqualTo(1);
        assertThat(((Map<?, ?>) all.get("total")).get("value")).isEqualTo(10.0d);
        assertThat(((Map<?, ?>) one.get("total")).get("value")).isEqualTo(5.0d);
        assertThat(((Number) ((Map<?, ?>) one.get("coverage")).get("dataMeters")).longValue()).isEqualTo(1L);
    }

    @Test
    void comparisonKeepsAbsoluteDiffAndExplainsZeroBaseline() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-10-01 08:00:00", "meter-1", 100),
                row("b", "2026-10-02 08:00:00", "meter-1", 100),
                row("c", "2026-10-03 08:00:00", "meter-1", 110)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "scopeLevel", "location", "meterId", "meter-1",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00",
                        "baseFrom", "2026-10-01T08:00:00+08:00", "baseTo", "2026-10-02T08:00:00+08:00"))).block().payload();
        Map<?, ?> diff = (Map<?, ?>) payload.get("diff");
        assertThat(diff.get("value")).isEqualTo(10.0d);
        assertThat(diff.get("pct")).isNull();
        assertThat(diff.get("pctNote")).isEqualTo("基期为零");
        assertThat(diff.get("basis")).isEqualTo("total");
        assertThat(((Map<?, ?>) payload.get("params")).get("axis")).isEqualTo("time");
    }

    @Test
    void timeComparisonUsesOnlyTheCommonMeterCohort() {
        CompanyEnergyObservationComposer composer = composerWith(page(List.of(
                row("a", "2026-10-01 08:00:00", "meter-1", 100),
                row("b", "2026-10-02 08:00:00", "meter-1", 100),
                row("c", "2026-10-03 08:00:00", "meter-1", 110),
                row("d", "2026-10-02 08:00:00", "meter-2", 50),
                row("e", "2026-10-03 08:00:00", "meter-2", 60)), true), List.of(METER, SECOND));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "axis", "time", "scopeLevel", "all",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00",
                        "baseFrom", "2026-10-01T08:00:00+08:00", "baseTo", "2026-10-02T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) ((Map<?, ?>) payload.get("sides")).get("a")).get("dataMeters")).isEqualTo(1);
        assertThat(((Map<?, ?>) payload.get("coverage")).get("dataMeters")).isEqualTo(1);
        assertThat((List<?>) ((Map<?, ?>) payload.get("coverage")).get("partial")).hasSize(1);
        assertThat(((Map<?, ?>) payload.get("diff")).get("value")).isEqualTo(10.0d);
    }

    @Test
    void regionComparisonUsesSameWindowAndNormalizesPerMeter() {
        CompanyEnergyObservationComposer composer = composerWith(page(List.of(
                row("a", "2026-10-02 08:00:00", "meter-1", 10),
                row("b", "2026-10-03 08:00:00", "meter-1", 20),
                row("c", "2026-10-02 08:00:00", "meter-2", 10),
                row("d", "2026-10-03 08:00:00", "meter-2", 15)), true), List.of(METER, SECOND));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "axis", "region", "scopeLevel", "location", "meterId", "meter-1",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00",
                        "otherScopeLevel", "floor", "otherFloor", "27楼"))).block().payload();

        assertThat(((Map<?, ?>) payload.get("params")).get("axis")).isEqualTo("region");
        assertThat(payload.get("normalized")).isEqualTo(true);
        assertThat(((Map<?, ?>) payload.get("diff")).get("basis")).isEqualTo("perMeterDaily");
        assertThat(((Map<?, ?>) payload.get("diff")).get("value")).isEqualTo(5.0d);
    }

    @Test
    void nightStatisticsUsesOnlyCompleteTwentyToEightWindows() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-10-01 20:00:00", "meter-1", 100),
                row("b", "2026-10-02 08:00:00", "meter-1", 112),
                row("c", "2026-10-02 20:00:00", "meter-1", 120),
                row("d", "2026-10-03 08:00:00", "meter-1", 130)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "windowFrom", "20:00", "windowTo", "08:00",
                        "from", "2026-10-01T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) payload.get("total")).get("value")).isEqualTo(22.0d);
        assertThat(((Map<?, ?>) ((Map<?, ?>) payload.get("params")).get("dailyWindow")).get("from")).isEqualTo("20:00");
        assertThat(((Map<?, ?>) payload.get("windowSummary")).get("expectedNights")).isEqualTo(2);
        assertThat(((Map<?, ?>) ((List<?>) payload.get("meters")).get(0)).get("night")).isNotNull();
    }


    @Test
    void nightComparisonUsesOnlyCompleteTwentyToEightWindows() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-09-30 20:00:00", "meter-1", 80),
                row("b", "2026-10-01 08:00:00", "meter-1", 90),
                row("c", "2026-10-01 20:00:00", "meter-1", 100),
                row("d", "2026-10-02 08:00:00", "meter-1", 112)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "axis", "time", "scopeLevel", "all",
                        "windowFrom", "20:00", "windowTo", "08:00",
                        "from", "2026-10-01T08:00:00+08:00", "to", "2026-10-02T08:00:00+08:00",
                        "baseFrom", "2026-09-30T08:00:00+08:00", "baseTo", "2026-10-01T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) ((Map<?, ?>) payload.get("params")).get("dailyWindow")).get("from")).isEqualTo("20:00");
        assertThat(((Map<?, ?>) payload.get("diff")).get("value")).isEqualTo(2.0d);
        assertThat(((Map<?, ?>) payload.get("diff")).get("basis")).isEqualTo("total");
        assertThat(((Map<?, ?>) ((Map<?, ?>) payload.get("sides")).get("a")).get("dataMeters")).isEqualTo(1);
    }

    @Test
    void arbitraryRangeAndWeekGrainAreServerCalculated() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-09-01 08:00:00", "meter-1", 100),
                row("b", "2026-09-08 08:00:00", "meter-1", 110),
                row("c", "2026-09-15 08:00:00", "meter-1", 120),
                row("d", "2026-09-22 08:00:00", "meter-1", 130),
                row("e", "2026-09-29 08:00:00", "meter-1", 140),
                row("f", "2026-10-01 08:00:00", "meter-1", 145)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "grain", "week",
                        "from", "2026-09-01T08:00:00+08:00", "to", "2026-10-01T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) payload.get("total")).get("value")).isEqualTo(45.0d);
        assertThat(((Map<?, ?>) payload.get("params")).get("grain")).isEqualTo("week");
        assertThat(((Map<?, ?>) payload.get("buckets")).get("grain")).isEqualTo("week");
        assertThat((List<?>) ((Map<?, ?>) payload.get("buckets")).get("items")).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void monthlyReportDoesNotInventACompanyAggregateWhenAMeterMonthIsMissing() {
        CompanyEnergyObservationComposer composer = composer(page(List.of(
                row("a", "2026-07-01 08:00:00", "meter-1", 100),
                row("b", "2026-08-01 08:00:00", "meter-1", 110),
                row("c", "2026-09-01 08:00:00", "meter-1", 130)), true));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_REPORT,
                Map.of("months", "2", "endMonth", "2026-09", "boundaryHour", "8"))).block().payload();

        assertThat(payload.get("type")).isEqualTo("company-energy");
        List<?> periods = (List<?>) payload.get("periods");
        assertThat(((Map<?, ?>) periods.get(0)).get("metric")).isNotNull();
        assertThat(((Map<?, ?>) ((Map<?, ?>) periods.get(0)).get("metric")).get("value")).isEqualTo(20.0d);
        assertThat(((Map<?, ?>) periods.get(1)).get("metric")).isNull();
        assertThat(((Map<?, ?>) payload.get("aggregation")).get("status")).isEqualTo("verified");
    }

    @Test
    void dailyReadsUnifiedPagesWithoutMeterFanOut() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Map<String, String>> firstParameters = new AtomicReference<>();
        List<AuthorizedDevice> meters = List.of(METER, SECOND,
                new AuthorizedDevice("meter-3", "energy", "三号表", "28楼", true));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> {
                    calls.incrementAndGet();
                    if (pageNumber == 0) firstParameters.set(request.parameters());
                    return Mono.just(ObservationResponse.available(pageBody(List.of(
                            row("r" + pageNumber, "2026-10-01 08:00:00", "meter-1", 100)), true)));
                }),
                iot(COMPANY, meters), CLOCK);

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "30"))).block();

        assertThat(response).isNotNull();
        assertThat(calls.get()).isEqualTo(1);
        assertThat(firstParameters.get()).doesNotContainKey("equipmentId");
    }

    private static CompanyEnergyObservationComposer composer(PageFixture fixture) {
        return composerWith(fixture, List.of(METER));
    }

    private static CompanyEnergyObservationComposer composerWith(PageFixture fixture, List<AuthorizedDevice> meters) {
        return CompanyEnergyObservationComposerFactory.composed(raw(fixture), iot(COMPANY, meters), CLOCK);
    }

    private static ObservationAdapter raw(PageFixture fixture) {
        return request -> fixture.read(request, Integer.parseInt(request.parameters().getOrDefault("page", "0")));
    }

    private static PageFixture page(List<Map<String, Object>> rows, boolean last) {
        Map<String, Object> body = pageBody(rows, last);
        return (request, pageNumber) -> Mono.just(ObservationResponse.available(body));
    }

    private static IotAdapter iot(RequestIdentity identity, List<AuthorizedDevice> devices) {
        return request -> Mono.just(new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return identity; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return devices; }
        });
    }

    private static Map<String, Object> pageBody(List<Map<String, Object>> rows, boolean last) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("content", rows);
        page.put("last", last);
        return page;
    }

    private static Map<String, Object> row(String id, String readTime, String meterId, double epf) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("equipmentId", meterId);
        row.put("readTime", readTime);
        row.put("epf", epf);
        return row;
    }

    @FunctionalInterface
    private interface PageFixture {
        Mono<ObservationResponse> read(ObservationRequest request, int page);
    }
}
