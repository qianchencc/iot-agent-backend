package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
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
                pageNumber == 0 ? page(List.of(
                        row("previous", "2026-10-02 08:00:00", 0),
                        row("start", "2026-10-03 08:00:00", 100),
                        row("evening", "2026-10-03 20:00:00", 110),
                        row("end", "2026-10-04 08:00:00", 125)), true) : empty())));

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
    void failedStreamPageCannotBecomeACompleteDailySnapshot() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> pageNumber == 1
                ? Mono.error(new ObservationUnavailableException("历史页不可用"))
                : Mono.just(ObservationResponse.available(page(List.of(
                        row("a", "2026-10-02 08:00:00", 100),
                        row("b", "2026-10-03 08:00:00", 125)), false))));

        assertThatThrownBy(() -> composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-03"))).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void denseSamplingDoesNotTruncateTheRequestedWindowUsingAPredictedPageCount() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) -> {
            if (pageNumber < 12) {
                return Mono.just(ObservationResponse.available(page(List.of(
                        row("recent-" + pageNumber, "2026-10-08 08:00:00", 200)), false)));
            }
            return Mono.just(ObservationResponse.available(page(List.of(
                    row("start", "2026-10-03 08:00:00", 100),
                    row("end", "2026-10-04 08:00:00", 125)), true)));
        });

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-03"))).block().payload();

        assertThat(((Map<?, ?>) ((List<?>) payload.get("days")).get(0)).get("usage")).isEqualTo(25.0d);
        assertThat(payload.get("truncated")).isEqualTo(false);
    }

    @Test
    void exhaustedStreamBudgetRemainsPartialInsteadOfACompleteSnapshot() {
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw((request, pageNumber) -> Mono.just(ObservationResponse.available(page(List.of(
                        row("recent-" + pageNumber, "2026-10-08 08:00:00", 200)), false)))),
                iot(COMPANY, List.of(METER)), CLOCK, 1, 100);

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-03"))).block();

        assertThat(response.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(response.payload().get("truncated")).isEqualTo(true);
        assertThat(((Map<?, ?>) ((List<?>) response.payload().get("days")).get(0)).get("usage")).isNull();
    }

    @Test
    void normalContinuationPagesCompleteAUnifiedMultiMeterDailyResult() {
        org.springframework.web.reactive.function.client.ExchangeFunction gateway = request ->
                Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                        .create(org.springframework.http.HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(request.url().getQuery().contains("page=0") ? """
                                {"code":200,"content":{"last":false,"content":[
                                  {"id":"a1","equipmentId":"meter-1","readTime":"2026-10-03 08:00:00","epf":100},
                                  {"id":"a2","equipmentId":"meter-1","readTime":"2026-10-04 08:00:00","epf":125},
                                  {"id":"b1","equipmentId":"meter-2","readTime":"2026-10-03 08:00:00","epf":200},
                                  {"id":"b2","equipmentId":"meter-2","readTime":"2026-10-04 08:00:00","epf":240}
                                ]}}
                                """ : "{\"code\":200,\"content\":{\"last\":true,\"content\":[]}}")
                        .build());
        ObservationAdapter history = ObservationAdapterFactory.webClient(
                org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(gateway).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperation.ENERGY_HISTORY, "/history")),
                java.time.Duration.ofSeconds(1), IotCallPolicy.passThrough());
        AuthorizedDevice second = new AuthorizedDevice("meter-2", "energy", "二号表", "27楼", true);
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                history, iot(COMPANY, List.of(METER, second)), CLOCK);

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-03"))).block();

        assertThat(response.status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(response.payload().get("truncated")).isEqualTo(false);
        java.util.List<?> meters = (java.util.List<?>) response.payload().get("meters");
        java.util.Map<String, Double> usage = new java.util.HashMap<>();
        for (Object row : meters) {
            Map<?, ?> meter = (Map<?, ?>) row;
            Map<?, ?> day = (Map<?, ?>) ((Map<?, ?>) meter.get("daily")).get("2026-10-03");
            usage.put((String) meter.get("id"), ((Number) day.get("usage")).doubleValue());
        }
        assertThat(usage).containsEntry("meter-1", 25.0d).containsEntry("meter-2", 40.0d);
    }

    @Test
    void reportAuthorizationRejectionIsNotConvertedIntoMissingMeterData() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) ->
                Mono.error(new GatewayAuthorizationException(403)));

        assertThatThrownBy(() -> composer.readEnergyReport(new ObservationRequest(COMPANY,
                ObservationOperation.ENERGY_REPORT, Map.of("kind", "statistics",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block())
                .isInstanceOf(GatewayAuthorizationException.class);
    }

    @Test
    void failedMeterReportCannotBePublishedAsACompleteAvailableSnapshot() {
        CompanyEnergyObservationComposer composer = composer((request, pageNumber) ->
                Mono.error(new ObservationUnavailableException("读取失败")));

        ObservationResponse response = composer.readEnergyReport(new ObservationRequest(COMPANY,
                ObservationOperation.ENERGY_REPORT, Map.of("kind", "statistics",
                        "from", "2026-10-02T08:00:00+08:00", "to", "2026-10-03T08:00:00+08:00"))).block();

        assertThat(response.status()).isEqualTo(ObservationStatus.PARTIAL);
        assertThat(response.coverageComplete()).isFalse();
        assertThat(((Map<?, ?>) response.payload().get("coverage")).get("truncated")).isEqualTo(true);
    }

    @Test
    void monthlyHistoryKeepsItsBoundaryAndDoesNotRequireUnrelatedOlderPages() {
        org.springframework.web.reactive.function.client.ExchangeFunction gateway = request -> {
            if (!request.url().getQuery().contains("page=0")) {
                return Mono.error(new IllegalStateException("Older unrelated archive is unavailable"));
            }
            return Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK).header("Content-Type", "application/json")
                    .body("""
                            {"code":200,"content":{"last":false,"content":[
                              {"id":"d","equipmentId":"meter-1","readTime":"2026-10-01 08:00:00","epf":160},
                              {"id":"c","equipmentId":"meter-1","readTime":"2026-09-01 08:00:00","epf":130},
                              {"id":"b","equipmentId":"meter-1","readTime":"2026-08-01 08:00:00","epf":110},
                              {"id":"a","equipmentId":"meter-1","readTime":"2026-06-30 18:00:00","epf":100}
                            ]}}
                            """).build());
        };
        ObservationAdapter history = ObservationAdapterFactory.webClient(
                org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(gateway).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperation.ENERGY_HISTORY, "/history")),
                java.time.Duration.ofSeconds(1), IotCallPolicy.passThrough());
        var composer = CompanyEnergyObservationComposerFactory.composed(
                history, iot(COMPANY, List.of(METER)), CLOCK);
        var response = composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_ENERGY_REPORT,
                Map.of("months", "2", "endMonth", "2026-09", "boundaryHour", "8"))).block();
        assertThat(response.status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(response.payload().get("truncated")).isEqualTo(false);
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) response.payload().get("meters")).get(0);
        assertThat(((Map<?, ?>) ((List<?>) meter.get("periods")).get(1)).get("usage")).isEqualTo(30.0d);
    }

    @Test
    void coveredHistoricalSubwindowReusesHeldDaysWithoutUpstreamRead() {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            if (fail.get()) {
                return Mono.error(new ObservationUnavailableException("已覆盖历史不应重新读取"));
            }
            return Mono.just(ObservationResponse.available(page(historicalRows(request), true)));
        };
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK);

        Map<String, Object> first = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "90", "end", "2026-10-03"))).block().payload();
        assertThat(first.get("truncated")).isEqualTo(false);
        int heldCalls = calls.get();
        fail.set(true);

        Map<String, Object> second = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2026-10-03"))).block().payload();

        assertThat(calls.get()).isEqualTo(heldCalls);
        List<?> days = (List<?>) second.get("days");
        assertThat(days).hasSize(30).allSatisfy(value -> {
            Map<?, ?> day = (Map<?, ?>) value;
            assertThat(day.get("complete")).isEqualTo(true);
            assertThat(day.get("usage")).isEqualTo(10.0d);
        });
    }

    @Test
    void adjacentOlderHistoricalWindowFetchesOnlyTheMissingBoundary() {
        List<Map<String, String>> requests = new java.util.ArrayList<>();
        ObservationAdapter raw = request -> {
            requests.add(Map.copyOf(request.parameters()));
            return Mono.just(ObservationResponse.available(page(historicalRows(request), true)));
        };
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK);

        Map<String, Object> first = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "90", "end", "2026-10-03"))).block().payload();
        String firstWindowFrom = String.valueOf(((Map<?, ?>) first.get("window")).get("from"));
        int firstCount = requests.size();

        Map<String, Object> second = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "90", "end", "2026-10-02"))).block().payload();

        List<Map<String, String>> incremental = requests.subList(firstCount, requests.size());
        assertThat(incremental).isNotEmpty().allSatisfy(request -> {
            assertThat(request.get("from")).as("incremental history must be date-bounded").isNotNull();
            assertThat(Instant.parse(request.get("from")))
                    .isBefore(Instant.parse(firstWindowFrom));
        });
        List<?> days = (List<?>) second.get("days");
        assertThat(days).hasSize(90).allSatisfy(value -> {
            Map<?, ?> day = (Map<?, ?>) value;
            assertThat(day.get("complete")).isEqualTo(true);
            assertThat(day.get("usage")).isEqualTo(10.0d);
        });
    }

    @Test
    void snapshotBackedDailyReusesPersistedMonthBlocksForCoveredHistory() {
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            if (fail.get()) {
                return Mono.error(new ObservationUnavailableException("已持久化月块不应重新读取"));
            }
            return Mono.just(ObservationResponse.available(page(historicalRows(request), true)));
        };
        InMemoryBlocks blocks = new InMemoryBlocks();
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, blocks);

        Map<String, Object> first = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "90", "end", "2026-10-03"))).block().payload();
        assertThat(first.get("truncated")).isEqualTo(false);
        int heldCalls = calls.get();
        fail.set(true);

        Map<String, Object> second = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2026-10-03"))).block().payload();
        assertThat(calls.get()).isEqualTo(heldCalls);
        assertThat((List<?>) second.get("days")).hasSize(30).allSatisfy(value -> {
            Map<?, ?> day = (Map<?, ?>) value;
            assertThat(day.get("complete")).isEqualTo(true);
            assertThat(day.get("usage")).isEqualTo(10.0d);
        });
    }

    @Test
    void sparseBoundarySampleBeforeMonthStartKeepsDailyUsage() {
        ObservationAdapter raw = request -> Mono.just(ObservationResponse.available(
                page(filteredRows(request, List.of(
                        row("sparse-1", "2026-09-28 08:00:00", 0),
                        row("sparse-2", "2026-10-01 08:00:00", 40),
                        row("sparse-3", "2026-10-02 08:00:00", 50))), true)));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, snapshots(CLOCK));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "2", "end", "2026-10-01"))).block().payload();

        List<?> days = (List<?>) payload.get("days");
        assertThat(((Map<?, ?>) days.get(0)).get("usage")).isEqualTo(40.0d);
        assertThat(((Map<?, ?>) days.get(1)).get("usage")).isEqualTo(10.0d);
        assertThat(days).allSatisfy(value -> assertThat(((Map<?, ?>) value).get("complete")).isEqualTo(true));
    }

    @Test
    void currentMonthLoaderRecomputesCutoffForNewReadings() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-08T02:00:00Z"), ZoneOffset.UTC);
        ObservationAdapter raw = request -> {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            rows.add(row("current-1", "2026-10-07 08:00:00", 100));
            rows.add(row("current-2", "2026-10-08 08:00:00", 110));
            if (!clock.instant().isBefore(Instant.parse("2026-10-08T03:00:00Z"))) {
                rows.add(row("current-3", "2026-10-08 11:00:00", 130));
            }
            return Mono.just(ObservationResponse.available(page(filteredRows(request, rows), true)));
        };
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), clock, snapshots(clock));

        Map<String, Object> first = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-07"))).block().payload();
        assertThat(java.time.OffsetDateTime.parse((String) first.get("latestSampleTime")).toInstant())
                .isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));

        clock.advance(Duration.ofHours(2));
        Map<String, Object> second = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-07"))).block().payload();
        assertThat(java.time.OffsetDateTime.parse((String) second.get("latestSampleTime")).toInstant())
                .isEqualTo(Instant.parse("2026-10-08T03:00:00Z"));
    }

    @Test
    void failedAnchorIsNotCachedAndRetriesAfterRecovery() {
        java.util.concurrent.atomic.AtomicBoolean failAnchor = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        List<Map<String, Object>> monthly = List.of(
                row("sep-10", "2026-09-10 08:00:00", 100),
                row("oct-01", "2026-10-01 08:00:00", 200),
                row("oct-02", "2026-10-02 08:00:00", 210));
        ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            Map<String, String> parameters = request.parameters();
            if (parameters.get("equipmentId") != null) {
                if (failAnchor.get()) {
                    return Mono.error(new ObservationUnavailableException("边界锚点暂不可用"));
                }
                return Mono.just(ObservationResponse.available(page(
                        List.of(row("anchor", "2026-08-31 08:00:00", 0)), true)));
            }
            return Mono.just(ObservationResponse.available(page(filteredRows(request, monthly), true)));
        };
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, snapshots(CLOCK));
        ObservationRequest request = new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2026-10-03"));

        assertThatThrownBy(() -> composer.read(request).block())
                .isInstanceOf(ObservationUnavailableException.class);
        int failedCalls = calls.get();

        failAnchor.set(false);
        Map<String, Object> payload = composer.read(request).block().payload();

        assertThat(calls.get()).isGreaterThan(failedCalls);
        assertThat(payload.get("truncated")).isEqualTo(false);
        Map<?, ?> day = ((List<?>) payload.get("days")).stream()
                .map(value -> (Map<?, ?>) value)
                .filter(value -> "2026-10-01".equals(value.get("date")))
                .findFirst().orElseThrow();
        assertThat(day.get("usage")).isEqualTo(10.0d);
        assertThat(day.get("complete")).isEqualTo(true);
    }

    @Test
    void shiftedHistoricalWindowsReuseCachedMonthAnchorsWithoutFurtherUpstream() {
        java.util.concurrent.atomic.AtomicBoolean reject = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        List<Map<String, Object>> monthly = List.of(
                row("sep-10", "2026-09-10 08:00:00", 100),
                row("oct-01", "2026-10-01 08:00:00", 200),
                row("oct-02", "2026-10-02 08:00:00", 210));
        ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            if (reject.get()) {
                return Mono.error(new ObservationUnavailableException("已缓存月块不应再次读取"));
            }
            Map<String, String> parameters = request.parameters();
            if (parameters.get("equipmentId") != null) {
                return Mono.just(ObservationResponse.available(page(
                        List.of(row("anchor", "2026-08-31 08:00:00", 0)), true)));
            }
            return Mono.just(ObservationResponse.available(page(filteredRows(request, monthly), true)));
        };
        InMemoryBlocks blocks = new InMemoryBlocks();
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, blocks);

        Map<String, Object> first = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2026-10-03"))).block().payload();
        assertThat(first.get("truncated")).isEqualTo(false);
        int held = calls.get();
        reject.set(true);

        Map<String, Object> contained = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "29", "end", "2026-10-02"))).block().payload();
        Map<String, Object> shifted = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2026-10-02"))).block().payload();

        assertThat(calls.get()).isEqualTo(held);
        assertThat(contained.get("truncated")).isEqualTo(false);
        assertThat(shifted.get("truncated")).isEqualTo(false);
        assertThat((List<?>) contained.get("days")).hasSize(29);
        assertThat((List<?>) shifted.get("days")).hasSize(30);
    }

    @Test
    void historicalWindowKeepsCurrentMonthLatestReadings() {
        ObservationAdapter raw = request -> Mono.just(ObservationResponse.available(
                page(filteredRows(request, List.of(
                        row("sep-29", "2026-09-29 08:00:00", 100),
                        row("sep-30", "2026-09-30 08:00:00", 110),
                        row("oct-01", "2026-10-01 08:00:00", 120),
                        row("oct-07", "2026-10-07 08:00:00", 180))), true)));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, snapshots(CLOCK));

        Map<String, Object> payload = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-09-30"))).block().payload();

        assertThat(java.time.OffsetDateTime.parse((String) payload.get("latestSampleTime")).toInstant())
                .isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        Map<?, ?> meter = (Map<?, ?>) ((List<?>) payload.get("meters")).get(0);
        assertThat(java.time.OffsetDateTime.parse((String) meter.get("latest")).toInstant())
                .isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        Map<?, ?> currentDay = (Map<?, ?>) ((Map<?, ?>) payload.get("comparison")).get("currentDay");
        assertThat(currentDay).isNotNull();
        assertThat(currentDay.get("date")).isEqualTo("2026-09-30");
        assertThat(currentDay.get("usage")).isEqualTo(10.0d);
    }

    @Test
    void olderMonthUsesUnfilteredHistoryBeyondThePageReadBudget() {
        Instant latest = Instant.parse("2026-11-02T00:00:00Z");
        java.time.format.DateTimeFormatter shanghai =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        AtomicInteger calls = new AtomicInteger();
        ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            Map<String, String> parameters = request.parameters();
            // The customer's date parameters select a different population, not readTime.
            // Returning an empty filtered page must not erase the unfiltered history.
            if (parameters.containsKey("from") || parameters.containsKey("to")) {
                return Mono.just(ObservationResponse.empty(Map.of()));
            }
            int page = Integer.parseInt(parameters.getOrDefault("page", "0"));
            int total = 100_000;
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (int index = page * 500; index < Math.min(total, (page + 1) * 500); index++) {
                Instant at = latest.minusSeconds(index * 900L);
                rows.add(row("sample-" + index, at.atZone(ZoneOffset.ofHours(8)).format(shanghai),
                        at.toEpochMilli() / 3_600_000.0));
            }
            return Mono.just(ObservationResponse.available(Map.of("content", rows,
                    "last", (page + 1) * 500 >= total, "totalElements", total)));
        };
        Clock now = Clock.fixed(latest, ZoneOffset.UTC);
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), now, snapshots(now));

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2025-09-19"))).block();

        Map<?, ?> day = (Map<?, ?>) ((List<?>) response.payload().get("days")).get(0);
        assertThat(day.get("usage")).isEqualTo(24.0d);
        assertThat(day.get("complete")).isEqualTo(true);
        assertThat(response.payload().get("truncated")).isEqualTo(false);
        int firstWindowCalls = calls.get();
        composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "2", "end", "2025-09-20"))).block();
        assertThat(calls.get()).isEqualTo(firstWindowCalls);
    }

    @Test
    void snapshotBackedDailyCompletesWhenSubscribedOnANonBlockingScheduler() {
        ObservationAdapter raw = request -> Mono.just(ObservationResponse.available(
                page(historicalRows(request), true)));
        IotAdapter async = request -> Mono.<AuthorizedDeviceScope>just(new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return COMPANY; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return List.of(METER); }
        }).subscribeOn(reactor.core.scheduler.Schedulers.parallel());
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, async, CLOCK, snapshots(CLOCK));

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                        ObservationOperation.COMPANY_ENERGY_DAILY,
                        Map.of("days", "2", "end", "2026-10-03")))
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
                .block();

        assertThat(response.status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(response.payload().get("truncated")).isEqualTo(false);
        assertThat((List<?>) response.payload().get("days")).hasSize(2).allSatisfy(value -> {
            Map<?, ?> day = (Map<?, ?>) value;
            assertThat(day.get("complete")).isEqualTo(true);
            assertThat(day.get("usage")).isEqualTo(10.0d);
        });
    }

    @Test
    void emptyHistoricalStreamRemainsNotComputableWithoutInventingZero() {
        ObservationAdapter raw = request -> Mono.just(ObservationResponse.empty(Map.of()));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, iot(COMPANY, List.of(METER)), CLOCK, snapshots(CLOCK));

        ObservationResponse response = composer.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "30", "end", "2025-09-30"))).block();

        assertThat(response.status()).isEqualTo(ObservationStatus.AVAILABLE);
        assertThat(response.payload().get("truncated")).isEqualTo(false);
        assertThat((List<?>) response.payload().get("days")).hasSize(30).allSatisfy(value -> {
            Map<?, ?> day = (Map<?, ?>) value;
            assertThat(day.get("usage")).isNull();
            assertThat(day.get("complete")).isEqualTo(false);
        });
    }

    @Test
    void readableMeterProjectionIsReusedAcrossOuterQueriesWithoutControlRefs() {
        AtomicInteger scopeLoads = new AtomicInteger();
        IotAdapter counting = request -> {
            scopeLoads.incrementAndGet();
            return Mono.just(new AuthorizedDeviceScope() {
                @Override public RequestIdentity identity() { return COMPANY; }
                @Override public IotSource source() { return IotSource.COMPANY; }
                @Override public List<AuthorizedDevice> devices() { return List.of(METER); }
            });
        };
        InMemoryBlocks blocks = new InMemoryBlocks();
        ObservationAdapter raw = request -> Mono.just(ObservationResponse.available(
                page(historicalRows(request), true)));
        CompanyEnergyObservationComposer composer = CompanyEnergyObservationComposerFactory.composed(
                raw, counting, CLOCK, blocks);

        composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "2", "end", "2026-10-03"))).block();
        composer.read(new ObservationRequest(COMPANY, ObservationOperation.COMPANY_ENERGY_DAILY,
                Map.of("days", "1", "end", "2026-10-03"))).block();

        assertThat(scopeLoads.get()).isEqualTo(1);
        assertThat(blocks.bodies().values()).allSatisfy(body -> {
            String text = String.valueOf(body);
            assertThat(text).doesNotContain("controlRef").doesNotContain("hubDeviceId")
                    .doesNotContain("officeDeviceRef");
        });
    }

    private static ReadSnapshots snapshots(Clock clock) {
        return ReadSnapshots.create(clock, Runnable::run, Runnable::run, null, null,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
    }

    private static List<Map<String, Object>> filteredRows(
            ObservationRequest request, List<Map<String, Object>> rows) {
        String from = request.parameters().get("from");
        String to = request.parameters().get("to");
        Instant lower = from == null ? Instant.MIN : java.time.OffsetDateTime.parse(from).toInstant();
        Instant upper = to == null ? Instant.MAX : java.time.OffsetDateTime.parse(to).toInstant();
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            Instant at = java.time.LocalDateTime.parse(String.valueOf(row.get("readTime")),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.ofHours(8));
            if (!at.isBefore(lower) && !at.isAfter(upper)) result.add(row);
        }
        result.sort((left, right) -> String.valueOf(right.get("readTime"))
                .compareTo(String.valueOf(left.get("readTime"))));
        return result;
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;
        MutableClock(Instant now, ZoneId zone) { this.now = now; this.zone = zone; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId target) { return new MutableClock(now, target); }
        @Override public Instant instant() { return now; }
    }

    private static List<Map<String, Object>> historicalRows(ObservationRequest request) {
        String from = request.parameters().get("from");
        String to = request.parameters().get("to");
        Instant lower = from == null ? Instant.MIN : java.time.OffsetDateTime.parse(from).toInstant();
        Instant upper = to == null ? Instant.MAX : java.time.OffsetDateTime.parse(to).toInstant();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (java.time.LocalDate day = java.time.LocalDate.of(2026, 1, 1);
                !day.isAfter(java.time.LocalDate.of(2026, 10, 9)); day = day.plusDays(1)) {
            Instant at = day.atTime(8, 0).atZone(ZoneOffset.ofHours(8)).toInstant();
            if (at.isBefore(lower) || at.isAfter(upper)) continue;
            rows.add(row("history-" + day, day + " 08:00:00", day.toEpochDay() * 10d));
        }
        rows.sort((left, right) -> String.valueOf(right.get("readTime"))
                .compareTo(String.valueOf(left.get("readTime"))));
        return rows;
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

    private static final class InMemoryBlocks implements ReadSnapshots {
        private final Map<String, Map<String, Object>> bodies = new HashMap<>();

        @Override public Map<String, Object> read(ReadSnapshots.Identity identity, String realm, Map<String, ?> query,
                ReadSnapshots.Policy policy, Supplier<Map<String, Object>> loader) {
            throw new UnsupportedOperationException("composer only uses the background load seam");
        }

        @Override public Map<String, Object> load(ReadSnapshots.Identity identity, String realm, Map<String, ?> query,
                ReadSnapshots.Policy policy, Supplier<Map<String, Object>> loader) {
            String key = identity.tenantCode() + '\u0000' + identity.authorization() + '\u0000'
                    + identity.source() + '\u0000' + identity.ownerKey() + '\u0000' + realm + '\u0000' + query;
            return bodies.computeIfAbsent(key, ignored -> loader.get());
        }

        @Override public void invalidate(ReadSnapshots.Identity identity) { }
        @Override public void discard(ReadSnapshots.Identity identity) { }
        @Override public void revoke(ReadSnapshots.Identity identity, ltd.qianc.iotagent.error.BusinessException failure) { }
        @Override public void admit(ReadSnapshots.Identity identity) { }
        Map<String, Map<String, Object>> bodies() { return bodies; }

        @Override public void refreshActive() { }
        @Override public void close(boolean drainComplete) { }
    }
}
