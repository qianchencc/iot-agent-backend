package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvironmentTrendBehaviorTest {
    @Test void dailyTrendUsesShanghaiBoundariesKeepsRawExtremesAndDoesNotInventMissingDays() {
        try (var f = new EnvironmentHistoryFixture()) {
            f.rows = List.of(
                    EnvironmentHistoryFixture.row("late", "test-sensor", "2020-01-01T15:30:00Z", "10"),
                    EnvironmentHistoryFixture.row("first", "test-sensor", "2020-01-01T16:30:00Z", "20"),
                    EnvironmentHistoryFixture.row("last", "test-sensor", "2020-01-02T01:30:00Z", "30"),
                    EnvironmentHistoryFixture.row("missing", "test-sensor", "2020-01-02T02:00:00Z", null));
            f.publish();
            int calls = f.sourceCalls.get();
            f.unavailable = true;
            var result = f.history().read(query(f, "2020-01-01", "2020-01-16"));
            assertEquals("day", result.payload().get("resolution"));
            assertTrue(((List<?>) result.payload().get("samples")).isEmpty());
            var buckets = (List<?>) result.payload().get("buckets");
            assertEquals(16, buckets.size());
            var first = (Map<?, ?>) buckets.get(0);
            var second = (Map<?, ?>) buckets.get(1);
            assertEquals("2020-01-01T00:00+08:00", first.get("from"));
            var temperature = (Map<?, ?>) ((Map<?, ?>) second.get("metrics")).get("temp");
            assertEquals(0, new BigDecimal("25").compareTo((BigDecimal) temperature.get("mean")));
            assertEquals(2, temperature.get("count"));
            assertEquals(3, ((Map<?, ?>) ((Map<?, ?>) second.get("metrics")).get("humidity")).get("count"));
            var thirdMetrics = (Map<?, ?>) ((Map<?, ?>) buckets.get(2)).get("metrics");
            assertNull(((Map<?, ?>) thirdMetrics.get("temp")).get("mean"));
            var metric = ((List<?>) result.payload().get("metrics")).stream().map(x -> (Map<?, ?>) x)
                    .filter(m -> "temp".equals(m.get("key"))).findFirst().orElseThrow();
            assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) ((Map<?, ?>) metric.get("peak")).get("value")));
            assertEquals("2020-01-02T01:30:00Z", ((Map<?, ?>) ((Map<?, ?>) result.payload().get("latest")).get("temp")).get("at"));
            assertEquals(4, ((Map<?, ?>) result.payload().get("coverage")).get("readSamples"));
            assertEquals(calls, f.sourceCalls.get());
        }
    }

    @Test void rawAndThreeHourWindowsRemainDistinctAndOnlyTrendRequestsHaveThe180DayLimit() {
        try (var f = new EnvironmentHistoryFixture()) {
            f.rows = List.of(
                    EnvironmentHistoryFixture.row("late", null, "2020-01-01T15:30:00Z", "10"),
                    EnvironmentHistoryFixture.row("early", null, "2020-01-01T16:30:00Z", "20"));
            f.publish();
            var raw = f.history().read(query(f, "2020-01-01", "2020-01-02"));
            assertEquals("raw", raw.payload().get("resolution"));
            assertEquals(2, ((List<?>) raw.payload().get("samples")).size());
            var medium = f.history().read(query(f, "2020-01-01", "2020-01-03"));
            assertEquals("3h", medium.payload().get("resolution"));
            var buckets = (List<?>) medium.payload().get("buckets");
            assertEquals(24, buckets.size());
            assertEquals("2020-01-01T21:00+08:00", ((Map<?, ?>) buckets.get(7)).get("from"));
            assertEquals("2020-01-02T00:00+08:00", ((Map<?, ?>) buckets.get(8)).get("from"));
            assertEquals(180, ((List<?>) f.history().read(query(f, "2020-01-01", "2020-06-28")).payload().get("buckets")).size());
            assertEquals("INVALID_HISTORY_WINDOW", assertThrows(ltd.qianc.iotagent.error.BusinessException.class,
                    () -> f.history().read(query(f, "2020-01-01", "2020-06-29"))).code());
            var original = f.history().read(new ObservationRequest(f.identity(), ObservationOperationEnum.ENVIRONMENT_HISTORY,
                    Map.of("from", "2020-01-01T00:00:00+08:00", "to", "2020-12-31T23:59:59.999+08:00", "fullWindow", "true")));
            assertEquals(2, ((List<?>) original.payload().get("samples")).size());
        }
    }

    @Test void historyBoundsCoverAuthorizedRecordsOutsideTheSelectedEmptyWindow() {
        try (var f = new EnvironmentHistoryFixture()) {
            f.rows = List.of(
                    EnvironmentHistoryFixture.row("first", null, "2020-01-01T15:30:00Z", "10"),
                    EnvironmentHistoryFixture.row("last", null, "2020-01-03T01:00:00Z", "20"),
                    EnvironmentHistoryFixture.row("outside", null, "1999-01-01T00:00:00Z", "5"));
            f.publish();
            assertEquals(1, f.jdbc.update("UPDATE local_environment_samples SET scope_key=? WHERE source_record_id=?",
                    "outside-fixture-scope", "outside"));
            int calls = f.sourceCalls.get();
            f.unavailable = true;
            var empty = f.history().read(query(f, "2020-02-01", "2020-02-02"));
            assertTrue(((List<?>) empty.payload().get("samples")).isEmpty());
            var bounds = (Map<?, ?>) empty.payload().get("availableWindow");
            assertNotNull(bounds);
            assertEquals("2020-01-01T15:30:00Z", bounds.get("from"));
            assertEquals("2020-01-03T01:00:00Z", bounds.get("to"));
            assertEquals(calls, f.sourceCalls.get());
        }
    }

    private static ObservationRequest query(EnvironmentHistoryFixture f, String from, String to) {
        return new ObservationRequest(f.identity(), ObservationOperationEnum.ENVIRONMENT_HISTORY,
                Map.of("from", from + "T00:00:00+08:00", "to", to + "T23:59:59.999+08:00", "resolution", "auto"));
    }
}
