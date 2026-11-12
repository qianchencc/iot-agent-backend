package com.ehe.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;

/** Public package-root contract tests for the local Company energy seam. */
class CompanyEnergyObservationComposerContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT", IotSourceEnum.COMPANY);
    private static final AuthorizedDevice METER =
            new AuthorizedDevice("meter-1", "energy", "一号表", "26楼办公室", true);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void dailyAggregatesShanghaiEightHourBucketsWithoutZeroFilling() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                pageNumber == 0 ? page(List.of(
                        row("previous", "2026-10-02 08:00:00", 0),
                        row("start", "2026-10-03 08:00:00", 100),
                        row("evening", "2026-10-03 20:00:00", 110),
                        row("end", "2026-10-04 08:00:00", 125)), true) : empty())));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();

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
                ObservationOperationEnum.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();
        Map<?, ?> missingDay = (Map<?, ?>) ((List<?>) payload.get("days")).get(0);
        Map<?, ?> missingMeter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        assertThat(missingDay.get("usage")).isNull();
        assertThat(((Map<?, ?>) ((Map<?, ?>) missingMeter.get("daily")).get("2026-10-03")).get("status"))
                .isEqualTo("not_computable");
        assertThat(payload.get("truncated")).isEqualTo(false);
        CompanyEnergyObservationComposer stale = composer((request, page) -> Mono.just(ObservationResponse.available(
                page(List.of(row("old", "2026-09-01 08:00:00", 100)), true))));
        Map<String, Object> stalePayload = stale.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block().payload();
        assertThat(((Map<?, ?>) ((List<?>) stalePayload.get("days")).get(0)).get("usage")).isNull();
    }


    @Test
    void unauthorizedCurrentScopeFailsClosed() {
        RequestIdentity other = new RequestIdentity("Bearer other", "TENANT", IotSourceEnum.COMPANY);
        CompanyEnergyObservationComposer composer = compose(raw((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of()))), iot(other, List.of(METER)), CLOCK);

        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.COMPANY_ENERGY_DAILY, Map.of("days", "1", "end", "2026-10-03"))).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void selectedMeterOutsideCurrentScopeIsForbidden() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of())));
        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "selection", "meterIds", "meter-outside",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block())
                .isInstanceOfSatisfying(com.ehe.iotagent.error.BusinessException.class,
                        error -> assertThat(error.httpStatus()).isEqualTo(403));
    }


    @Test
    void statisticsSupportsAllAndSingleMeterTimeWindows() {
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
        ObservationAdapter raw = raw((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                row(request.parameters().get("equipmentId") + "-1", "2026-10-02 08:00:00", 10),
                row(request.parameters().get("equipmentId") + "-2", "2026-10-03 08:00:00", 15)), true))));
        CompanyEnergyObservationComposer composer = compose(raw, iot(COMPANY, List.of(METER, second)), CLOCK);

        Map<String, Object> all = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        Map<String, Object> one = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "location", "meterId", "meter-1", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();

        assertThat(((Map<?, ?>) all.get("params")).get("scope").toString()).contains("meterCount=2");
        assertThat(((Map<?, ?>) one.get("params")).get("scope").toString()).contains("meterCount=1");
        assertThat(((Map<?, ?>) one.get("total")).get("value")).isEqualTo(5.0d);
    }

    @Test
    void comparisonKeepsAbsoluteDiffAndExplainsZeroBaseline() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                row("a", "2026-10-01 08:00:00", 100), row("b", "2026-10-02 08:00:00", 100), row("c", "2026-10-03 08:00:00", 110)), true))));
        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "scopeLevel", "location", "meterId", "meter-1",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00",
                        "baseFrom", "2026-10-01T08:00:00+08:00", "baseTo", "2026-10-02T08:00:00+08:00"))).block().payload();
        Map<?, ?> diff = (Map<?, ?>) payload.get("diff");
        assertThat(diff.get("value")).isEqualTo(10.0d);
        assertThat(diff.get("pct")).isNull();
    }

    @Test
    void weeklyComparisonUsesTheSameCohortAndKeepsTheShortFinalSegmentExplicit() {
        List<Map<String, Object>> samples = new java.util.ArrayList<>();
        for (int year : List.of(2025, 2026)) {
            var start = java.time.LocalDate.of(year, 8, 1);
            for (int day = 0; day <= 61; day++) {
                samples.add(row(year + "-" + day, start.plusDays(day) + "T00:00:00+08:00",
                        100 + day * (year == 2026 ? 10 : 5)));
            }
        }
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) ->
                Mono.just(ObservationResponse.available(page(samples, true))));
        var report = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(composer.read(
                new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                        Map.of("kind", "comparison", "scopeLevel", "all",
                                "from", "2026-08-01T00:00:00+08:00", "to", "2026-10-01T00:00:00+08:00",
                                "baseFrom", "2025-08-01T00:00:00+08:00", "baseTo", "2025-10-01T00:00:00+08:00")))
                .block().payload());
        var weeks = report.path("periodComparisons").path("items");
        assertThat(weeks.size()).isEqualTo(9);
        assertThat(weeks.get(0).path("a").path("value").asDouble()).isEqualTo(70.0);
        assertThat(weeks.get(0).path("b").path("value").asDouble()).isEqualTo(35.0);
        assertThat(weeks.get(0).path("diff").path("pct").asDouble()).isEqualTo(100.0);
        assertThat(weeks.get(8).path("a").path("days").asInt()).isEqualTo(5);
        assertThat(weeks.get(8).path("b").path("days").asInt()).isEqualTo(5);
        assertThat(weeks.get(8).path("a").path("partial").asBoolean()).isTrue();
        assertThat(weeks.get(8).path("a").path("value").asDouble()).isEqualTo(50.0);
        assertThat(weeks.get(8).path("diff").path("pct").isNull()).isTrue();
        assertThat(report.path("sides").path("a").path("total").path("value").asDouble()).isEqualTo(610.0);
    }

    @Test
    void regionComparisonKeepsOneTimeWindowAndTwoAuthorizedScopes() {
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
        ObservationAdapter raw = raw((request, pageNumber) -> {
            String id = request.parameters().get("equipmentId");
            double usage = "meter-1".equals(id) ? 10 : 30;
            return Mono.just(ObservationResponse.available(page(List.of(
                    Map.of("id", id + "-a", "equipmentId", id, "readTime", "2026-10-02 08:00:00", "epf", 100),
                    Map.of("id", id + "-b", "equipmentId", id, "readTime", "2026-10-03 08:00:00", "epf", 100 + usage)), true)));
        });
        var composer = compose(raw, iot(COMPANY, List.of(METER, second)), CLOCK);
        Map<String, Object> result = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "axis", "region", "scopeLevel", "location", "meterId", "meter-1",
                        "otherScopeLevel", "location", "otherMeterId", "meter-2",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        assertThat(((Map<?, ?>) result.get("params")).get("axis")).isEqualTo("region");
        Map<?, ?> sides = (Map<?, ?>) result.get("sides");
        assertThat(((Map<?, ?>) ((Map<?, ?>) sides.get("a")).get("total")).get("value")).isEqualTo(10d);
        assertThat(((Map<?, ?>) ((Map<?, ?>) sides.get("b")).get("total")).get("value")).isEqualTo(30d);
        assertThat(((Map<?, ?>) result.get("diff")).get("value")).isEqualTo(-20d);
    }

    @Test
    void nightStatisticsCountsOnlyCompleteTwentyToEightWindows() {
        var composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                row("a", "2026-10-02 08:00:00", 100), row("b", "2026-10-02 20:00:00", 110),
                row("c", "2026-10-03 08:00:00", 125), row("d", "2026-10-03 20:00:00", 155)), true))));
        Map<String, Object> result = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "windowFrom", "20:00", "windowTo", "08:00",
                        "from", "2026-10-02T00:00:00+08:00", "to", "2026-10-04T00:00:00+08:00"))).block().payload();
        assertThat(((Map<?, ?>) result.get("total")).get("value")).isEqualTo(15d);
        Map<?, ?> summary = (Map<?, ?>) result.get("windowSummary");
        assertThat(summary.get("expectedNights")).isEqualTo(1);
        assertThat(summary.get("pairedNightUsage")).isEqualTo(15d);
        assertThat(summary.get("pairedDayUsage")).isEqualTo(25d);
        assertThat(((Map<?, ?>) ((List<?>) result.get("meters")).get(0)).get("night")).isNotNull();
    }

    @Test
    void timeComparisonUsesTheSameComparableMetersOnBothSides() {
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
        ObservationAdapter raw = raw((request, pageNumber) -> {
            String id = request.parameters().get("equipmentId");
            List<Map<String, Object>> samples = new java.util.ArrayList<>();
            if ("meter-1".equals(id)) {
                samples.add(Map.of("id", id + "-a", "equipmentId", id, "readTime", "2026-10-01 08:00:00", "epf", 100));
            }
            samples.add(Map.of("id", id + "-b", "equipmentId", id, "readTime", "2026-10-02 08:00:00", "epf", 110));
            samples.add(Map.of("id", id + "-c", "equipmentId", id, "readTime", "2026-10-03 08:00:00", "epf", "meter-1".equals(id) ? 130 : 210));
            return Mono.just(ObservationResponse.available(page(samples, true)));
        });
        var composer = compose(raw, iot(COMPANY, List.of(METER, second)), CLOCK);
        Map<String, Object> report = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "scopeLevel", "all", "from", "2026-10-02T08:00:00+08:00",
                        "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        Map<?, ?> sides = (Map<?, ?>) report.get("sides");
        assertThat(((Map<?, ?>) ((Map<?, ?>) sides.get("a")).get("total")).get("value")).isEqualTo(20d);
        assertThat(((Map<?, ?>) ((Map<?, ?>) sides.get("b")).get("total")).get("value")).isEqualTo(10d);
        assertThat(((Map<?, ?>) report.get("diff")).get("value")).isEqualTo(10d);
        assertThat(((Map<?, ?>) report.get("coverage")).get("dataMeters")).isEqualTo(1);
        assertThat((List<?>) ((Map<?, ?>) report.get("coverage")).get("partial")).hasSize(1);
    }

    @Test
    void regionComparisonNormalizesLoadAndRetainsOverlappingMembership() {
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼展厅", true);
        ObservationAdapter raw = raw((request, pageNumber) -> {
            String id = request.parameters().get("equipmentId");
            double rate = "meter-1".equals(id) ? 1 : 2;
            List<Map<String, Object>> samples = new java.util.ArrayList<>();
            var start = java.time.LocalDateTime.parse("2026-10-02T08:00:00");
            for (int hour = 0; hour <= 24; hour += 4) {
                samples.add(Map.of("id", id + "-" + hour, "equipmentId", id,
                        "readTime", start.plusHours(hour).atOffset(ZoneOffset.ofHours(8)).toString(), "epf", 100 + hour * rate));
            }
            return Mono.just(ObservationResponse.available(page(samples, true)));
        });
        var composer = compose(raw, iot(COMPANY, List.of(METER, second)), CLOCK);
        Map<String, Object> report = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "comparison", "axis", "region", "scopeLevel", "all", "otherScopeLevel", "location",
                        "otherMeterId", "meter-1", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        Map<?, ?> sides = (Map<?, ?>) report.get("sides");
        Map<?, ?> a = (Map<?, ?>) ((Map<?, ?>) sides.get("a")).get("shape24");
        Map<?, ?> b = (Map<?, ?>) ((Map<?, ?>) sides.get("b")).get("shape24");
        assertThat(a.get("perMeter")).isEqualTo(true);
        assertThat(((List<?>) a.get("rates")).get(8)).isEqualTo(1.5d);
        assertThat(((List<?>) b.get("rates")).get(8)).isEqualTo(1d);
        assertThat((List<?>) report.get("overlapMeterIds")).isEqualTo(List.of("meter-1"));
        assertThat(((Map<?, ?>) report.get("coverage")).get("meters")).isEqualTo(2);
    }

    @Test
    void statisticsPreservesTariffAndRejectsConflictingPowerFactorSamples() {
        AuthorizedDevice priced = new AuthorizedDevice("meter-1", "energy", "一号表", "26楼办公室", true,
                null, null, null, null, List.of(), List.of(), Map.of("tariff", 1.5d));
        ObservationAdapter raw = raw((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                Map.of("id", "a", "equipmentId", "meter-1", "readTime", "2026-10-02 08:00:00", "epf", 100, "totalPowerFactor", 0.8),
                Map.of("id", "b", "equipmentId", "meter-1", "readTime", "2026-10-02 12:00:00", "epf", 104, "totalPowerFactor", 0.9),
                Map.of("id", "conflict", "equipmentId", "meter-1", "readTime", "2026-10-02 12:00:00", "epf", 104, "totalPowerFactor", 0.1),
                Map.of("id", "c", "equipmentId", "meter-1", "readTime", "2026-10-02 16:00:00", "epf", 108, "totalPowerFactor", 0.6),
                Map.of("id", "d", "equipmentId", "meter-1", "readTime", "2026-10-03 08:00:00", "epf", 124, "totalPowerFactor", 0.4)), true))));
        var composer = compose(raw, iot(COMPANY, List.of(priced)), CLOCK);
        Map<String, Object> report = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "scopeLevel", "all", "from", "2026-10-02T08:00:00+08:00",
                        "to", "2026-10-03T08:00:00+08:00"))).block().payload();
        assertThat(((Map<?, ?>) report.get("total")).get("yuan")).isEqualTo(36d);
        Map<?, ?> factor = (Map<?, ?>) ((Map<?, ?>) ((List<?>) report.get("meters")).get(0)).get("powerFactor");
        assertThat(factor.get("mean")).isEqualTo(0.7d);
        assertThat(factor.get("min")).isEqualTo(0.6d);
        assertThat(factor.get("samples")).isEqualTo(2);
    }

    @Test
    void selectionCannotSilentlyDropAnUnauthorizedMeter() {
        assertThatThrownBy(() -> composer((request, pageNumber) -> Mono.just(ObservationResponse.empty(Map.of())))
                .read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                        Map.of("kind", "statistics", "scopeLevel", "selection", "meterIds", "meter-1,meter-outside",
                                "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block())
                .isInstanceOfSatisfying(com.ehe.iotagent.error.BusinessException.class,
                        failure -> assertThat(failure.httpStatus()).isEqualTo(403));
    }

    @Test
    void weeklyComparisonKeepsZeroBaselineUndefinedAndCurrentWeekSeparate() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> Mono.just(ObservationResponse.available(
                page(List.of(row("a", "2026-09-21 08:00:00", 100),
                        row("b", "2026-09-28 08:00:00", 100),
                        row("c", "2026-10-05 08:00:00", 110)), true))));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.ENERGY_COMPARISON,
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
                ObservationOperationEnum.COMPANY_ENERGY_REPORT,
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
    void historicalDailyWindowStillUsesTheLatestKnownSamplingMetadata() {
        var composer = composer((request, page) -> Mono.just(ObservationResponse.available(page(List.of(
                row("old-a", "2026-09-29 08:00:00", 100), row("old-b", "2026-09-30 08:00:00", 110),
                row("latest", "2026-10-08 08:00:00", 200)), true))));
        var response = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-09-29"))).block();
        assertThat(response.payload().get("latestSampleTime")).isEqualTo("2026-10-08T08:00:00+08:00");
        assertThat(((Map<?, ?>) ((List<?>) response.payload().get("days")).getFirst()).get("usage")).isEqualTo(10d);
    }

    @Test
    void incompleteLocalMeterCoverageIsNotReportedAsConfirmedNoData() {
        EnergyHistory history = new FormulaHistory(COMPANY, List.of(METER), List.of(), List.of(METER.id()));
        var composer = CompanyEnergyObservationComposerFactory.composed(history, CLOCK);
        var response = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_METER_REPORT,
                Map.of("kind", "statistics", "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block();
        Map<?, ?> coverage = (Map<?, ?>) response.payload().get("coverage");
        assertThat(response.status()).isEqualTo(ObservationStatusEnum.PARTIAL);
        assertThat((List<?>) coverage.get("noData")).isEmpty();
        assertThat((List<?>) coverage.get("partial")).hasSize(1);
    }

    @Test
    void localCalculationCanBeSubscribedOnANonBlockingScheduler() {
        var composer = composer((request, page) -> Mono.just(ObservationResponse.available(page(List.of(
                row("a", "2026-10-02 08:00:00", 10), row("b", "2026-10-03 08:00:00", 20)), true))));
        var response = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-02"))).subscribeOn(reactor.core.scheduler.Schedulers.parallel()).block();
        assertThat(((Map<?, ?>) ((List<?>) response.payload().get("days")).getFirst()).get("usage")).isEqualTo(10d);
    }

    @Test
    void sparseAnchorOutsideTheRequestedWindowIsKeptForBoundarySemantics() {
        var composer = composer((request, page) -> Mono.just(ObservationResponse.available(page(List.of(
                row("a", "2026-09-28 08:00:00", 0), row("b", "2026-10-01 08:00:00", 40),
                row("c", "2026-10-02 08:00:00", 50)), true))));
        var response = composer.read(new ObservationRequest(COMPANY, ObservationOperationEnum.COMPANY_ENERGY_DAILY,
                Map.of("days", "2", "end", "2026-10-01"))).block();
        List<?> days = (List<?>) response.payload().get("days");
        assertThat(((Map<?, ?>) days.getFirst()).get("usage")).isEqualTo(40d);
        assertThat(((Map<?, ?>) days.getLast()).get("usage")).isEqualTo(10d);
    }

    private static CompanyEnergyObservationComposer composer(PageFixture fixture) {
        return compose(raw(fixture), iot(COMPANY, List.of(METER)), CLOCK);
    }

    /** Loads synthetic fixture rows once during arrangement; calculations never drive a page loader. */
    private static CompanyEnergyObservationComposer compose(ObservationAdapter rows, IotAdapter catalog, Clock clock) {
        AuthorizedDeviceScope scope = catalog.loadAuthorizedDeviceScope(COMPANY).block();
        List<EnergyHistory.Sample> samples = new java.util.ArrayList<>();
        for (AuthorizedDevice meter : scope.devices()) {
            ObservationResponse response = rows.read(new ObservationRequest(COMPANY, ObservationOperationEnum.ENERGY_HISTORY,
                    Map.of("equipmentId", meter.id(), "page", "0"))).block();
            Object content = response.payload().get("content");
            if (!(content instanceof List<?> values)) continue;
            for (Object value : values) {
                Map<?, ?> row = (Map<?, ?>) value;
                String rawTime = String.valueOf(row.get("readTime"));
                Instant time = rawTime.contains("T") ? java.time.OffsetDateTime.parse(rawTime).toInstant()
                        : java.time.LocalDateTime.parse(rawTime.replace(' ', 'T')).toInstant(ZoneOffset.ofHours(8));
                samples.add(new EnergyHistory.Sample(meter.id(), String.valueOf(row.get("id")), false, time,
                        new java.math.BigDecimal(row.get("epf").toString()), null, null,
                        row.get("totalPowerFactor") == null ? null : new java.math.BigDecimal(row.get("totalPowerFactor").toString()), false));
            }
        }
        return CompanyEnergyObservationComposerFactory.composed(new FormulaHistory(scope.identity(), scope.devices(), samples, List.of()), clock);
    }

    private record FormulaHistory(RequestIdentity identity, List<AuthorizedDevice> meters,
                                  List<EnergyHistory.Sample> samples, List<String> missing) implements EnergyHistory {
        @Override public AuthorizedDeviceScope authorizedScope(RequestIdentity request) {
            return AuthorizedDeviceScope.of(identity, identity.source(), meters);
        }
        @Override public Window window(RequestIdentity request, List<String> ids, Instant from, Instant to, boolean latest) {
            return new Window(samples.stream().filter(sample -> ids.contains(sample.meterId()))
                    .filter(sample -> latest || !sample.readTime().isAfter(to)).toList(), missing, Map.of());
        }
        @Override public ObservationResponse read(ObservationRequest request) {
            throw new AssertionError("Formula contracts use window facts, not the raw-page API");
        }
    }

    private static ObservationAdapter raw(PageFixture fixture) {
        return request -> fixture.read(request, Integer.parseInt(request.parameters().getOrDefault("page", "0")));
    }

    private static IotAdapter iot(RequestIdentity identity, List<AuthorizedDevice> devices) {
        return request -> Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices));
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, boolean last) {
        return Map.of("content", rows, "last", last);
    }

    private static Map<String, Object> row(String id, String time, double epf) {
        return Map.of("id", id, "equipmentId", "meter-1", "readTime", time, "epf", epf);
    }

    private static Map<String, Object> empty() { return Map.of(); }

    @FunctionalInterface
    private interface PageFixture { Mono<ObservationResponse> read(ObservationRequest request, int page); }
}
