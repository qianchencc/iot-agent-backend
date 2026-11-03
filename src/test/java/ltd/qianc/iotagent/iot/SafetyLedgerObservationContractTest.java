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
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Regression boundaries for the composed Company safety ledger: owner-bound cursor
 * continuation without duplicates, honest counters for out-of-scope and
 * unverifiable devices, bounded deep-event resolution, and legitimate empty
 * results instead of failures.
 */
class SafetyLedgerObservationContractTest {
    private static final RequestIdentity COMPANY = new RequestIdentity("Bearer company", "TENANT-1", IotSource.COMPANY);
    private static final RequestIdentity OTHER_OWNER = new RequestIdentity("Bearer other", "TENANT-2", IotSource.COMPANY);
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String FROM = "1970-01-01T00:00:00.000Z";
    private static final String TO = "2026-10-17T00:00:00Z";

    @Test
    void cursorContinuationReturnsEveryRecordExactlyOnceAndEndsHonest() {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("a1", "gas-1", "2026-10-16T09:00:00Z"), alarm("a2", "gas-1", "2026-10-16T08:00:00Z"),
                        alarm("a3", "gas-1", "2026-10-16T07:00:00Z"), alarm("a4", "gas-1", "2026-10-15T07:00:00Z"),
                        alarm("a5", "gas-1", "2026-10-14T07:00:00Z")),
                1, List.of(alarm("a6", "gas-1", "2026-10-13T07:00:00Z"), alarm("a7", "gas-1", "2026-10-12T07:00:00Z"))));
        ObservationAdapter adapter = composed(upstream, iot(COMPANY, device("gas-1", "27楼茶水间燃气感应器", null)));

        List<String> seen = new ArrayList<>();
        Map<String, Object> first = ledger(adapter, Map.of("type", "gas", "limit", "2"));
        assertThat(items(first)).extracting(row -> row.get("eventId")).containsExactly("a1", "a2");
        assertThat(first.get("coverageComplete")).isEqualTo(false);
        assertThat(first.get("nextCursor")).asString().isNotBlank();
        assertThat(first.get("observedAt")).isEqualTo(NOW.toString());
        assertThat(window(first)).containsEntry("from", FROM).containsEntry("to", TO);
        seen.addAll(ids(first));

        String cursor = (String) first.get("nextCursor");
        Map<String, Object> second = ledger(adapter, Map.of(
                "type", "gas", "limit", "2", "from", FROM, "to", TO, "cursor", cursor));
        assertThat(items(second)).extracting(row -> row.get("eventId")).containsExactly("a3", "a4");
        assertThat(second.get("coverageComplete")).isEqualTo(false);
        seen.addAll(ids(second));

        Map<String, Object> third = ledger(adapter, Map.of(
                "type", "gas", "limit", "2", "from", FROM, "to", TO, "cursor", (String) second.get("nextCursor")));
        assertThat(items(third)).extracting(row -> row.get("eventId")).containsExactly("a5", "a6");
        assertThat(third.get("coverageComplete")).isEqualTo(false);
        seen.addAll(ids(third));

        Map<String, Object> fourth = ledger(adapter, Map.of(
                "type", "gas", "limit", "2", "from", FROM, "to", TO, "cursor", (String) third.get("nextCursor")));
        assertThat(items(fourth)).extracting(row -> row.get("eventId")).containsExactly("a7");
        assertThat(fourth.get("coverageComplete")).isEqualTo(true);
        assertThat(fourth.get("nextCursor")).isNull();
        seen.addAll(ids(fourth));

        assertThat(seen).containsExactly("a1", "a2", "a3", "a4", "a5", "a6", "a7");
        assertThat(seen.stream().distinct().count()).isEqualTo(seen.size());
        // Every continuation re-reads the upstream page it resumes inside; the
        // before-key guard must deduplicate, never skip or repeat records.
        assertThat(upstream.requestedPages).isNotEmpty();
    }

    @Test
    void cursorRejectsForeignOwnerTamperingAndMismatchedWindow() {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("a1", "gas-1", "2026-10-16T09:00:00Z"), alarm("a2", "gas-1", "2026-10-16T08:00:00Z")),
                1, List.of(alarm("a3", "gas-1", "2026-10-15T09:00:00Z"))));
        ObservationAdapter adapter = composed(upstream, iot(COMPANY, device("gas-1", "燃气感应器", null)));

        Map<String, Object> first = ledger(adapter, Map.of("type", "gas", "limit", "2"));
        assertThat(first.get("nextCursor")).asString().isNotBlank();
        String cursor = (String) first.get("nextCursor");

        ObservationAdapter otherOwnerAdapter = composed(upstream, iot(OTHER_OWNER, device("gas-1", "燃气感应器", null)));
        BusinessException foreign = catchBusiness(() -> otherOwnerAdapter.read(request(Map.of(
                "type", "gas", "limit", "1", "from", FROM, "to", TO, "cursor", cursor))).block());
        assertThat(foreign.code()).isEqualTo("INVALID_SAFETY_CURSOR");
        assertThat(foreign.httpStatus()).isEqualTo(400);

        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "1", "cursor", "not-a-cursor!!"))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "1", "cursor",
                java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"owner\":\"forged\"}".getBytes())))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
        // A continuation that silently changes the window must restart, not merge.
        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "1", "from", "2026-10-01T00:00:00Z", "to", TO, "cursor", cursor)))
                .block()).code()).isEqualTo("INVALID_SAFETY_CURSOR");
        // A cursor minted for one ledger type cannot be replayed against another.
        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "smoke", "limit", "1", "from", FROM, "to", TO, "cursor", cursor))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
    }

    @Test
    void editedCursorPayloadIsRejectedEvenForItsOwner() throws Exception {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("a1", "gas-1", "2026-10-16T09:00:00Z"), alarm("a2", "gas-1", "2026-10-16T08:00:00Z")),
                1, List.of(alarm("a3", "gas-1", "2026-10-15T09:00:00Z"))));
        ObservationAdapter adapter = composed(upstream, iot(COMPANY, device("gas-1", "燃气感应器", null)));
        String cursor = (String) ledger(adapter, Map.of("type", "gas", "limit", "2")).get("nextCursor");

        // A same-owner client decodes the payload, rewinds page/offset/window and
        // re-encodes it; without the server MAC key the forgery must fail closed.
        int separator = cursor.lastIndexOf('.');
        String payload = cursor.substring(0, separator);
        String signature = cursor.substring(separator + 1);
        com.fasterxml.jackson.databind.node.ObjectNode edited =
                (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(java.util.Base64.getUrlDecoder().decode(payload));
        edited.put("page", 0);
        edited.put("offset", 0);
        edited.put("before", "");
        edited.put("to", "2999-01-01T00:00:00Z");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(edited.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "2", "cursor", forgedPayload + "." + signature))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "2", "cursor", forgedPayload))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of(
                "type", "gas", "limit", "2", "cursor", cursor.substring(0, cursor.length() - 2)))).block()).code())
                .isEqualTo("INVALID_SAFETY_CURSOR");
    }

    @Test
    void ledgerFiltersByScopeSubtypeAndWindowWithHonestCounters() {
        PagedUpstream upstream = new PagedUpstream(Map.of(0, List.of(
                alarm("g1", "gas-1", "2026-10-14T08:00:00Z"),
                alarm("s1", "smoke-1", "2026-10-14T07:30:00Z"),
                alarm("ghost-1", "ghost", "2026-10-14T07:00:00Z"),
                alarm("u1", "plain-1", "2026-10-14T06:30:00Z"),
                alarm("g2", "gas-1", "2026-10-01T06:00:00Z"))));
        ObservationAdapter adapter = composed(upstream, iot(COMPANY,
                device("gas-1", "27楼茶水间燃气感应器", null),
                device("smoke-1", "27楼光电感烟探测器", null),
                device("plain-1", "消防传感器", null)));

        Map<String, Object> payload = ledger(adapter, Map.of(
                "type", "gas", "limit", "20", "from", "2026-10-13T00:00:00Z", "to", TO));
        assertThat(items(payload)).extracting(row -> row.get("eventId")).containsExactly("g1");
        assertThat(payload.get("excludedCount")).isEqualTo(2);
        assertThat(payload.get("unknownCount")).isEqualTo(1);
        assertThat(payload.get("scanned")).isEqualTo(5);
        assertThat(payload.get("coverageComplete")).isEqualTo(true);
        assertThat(payload.get("total")).isNull();
        assertThat(items(payload).get(0)).containsEntry("subtype", "gas");
    }

    @Test
    void deepEventResolvesAcrossPagesAndReportsBoundedMissesHonestly() {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("a0", "gas-1", "2026-10-16T10:00:00Z")),
                1, List.of(alarm("a1", "gas-1", "2026-10-16T09:00:00Z")),
                2, List.of(alarm("target", "gas-1", "2026-10-16T08:00:00Z")),
                3, List.of(alarm("a3", "gas-1", "2026-10-16T07:00:00Z")),
                4, List.of(alarm("a4", "gas-1", "2026-10-16T06:00:00Z"))));
        ObservationAdapter adapter = composed(upstream, iot(COMPANY, device("gas-1", "27楼茶水间燃气感应器", null)));

        Map<String, Object> payload = adapter.read(new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENT,
                        Map.of("id", "target", "ledger", "gas", "deviceId", "gas-1")))
                .block().payload();
        Map<String, Object> record = (Map<String, Object>) payload.get("record");
        assertThat(record).containsEntry("eventId", "target").containsEntry("deviceId", "gas-1")
                .containsEntry("recordedDeviceName", "27楼茶水间燃气感应器");
        // Without a verifiable current metadata source the fields stay honestly null.
        assertThat(currentDevice(record)).containsEntry("name", null).containsEntry("location", null);
        assertThat(record.get("deviceName")).isEqualTo("27楼茶水间燃气感应器");
        assertThat(record.get("location")).isEqualTo("");
        assertThat(payload.get("observedAt")).isEqualTo(NOW.toString());

        int readsBeforeMiss = upstream.reads.get();
        assertThatThrownBy(() -> adapter.read(new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENT,
                        Map.of("id", "missing", "ledger", "gas", "deviceId", "gas-1"))).block())
                .isInstanceOf(ObservationNotFoundException.class);
        assertThat(upstream.reads.get() - readsBeforeMiss).isEqualTo(5);

        PagedUpstream untouched = new PagedUpstream(Map.of());
        ObservationAdapter scoped = composed(untouched, iot(COMPANY));
        assertThatThrownBy(() -> scoped.read(new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENT,
                        Map.of("id", "target", "ledger", "gas", "deviceId", "gas-1"))).block())
                .isInstanceOf(ObservationNotFoundException.class)
                .hasMessageContaining("当前授权与查询范围内未找到这条记录");
        assertThat(untouched.reads.get()).isZero();
    }

    @Test
    void emptySafetyScopeIsALegitimateEmptyLedgerNotAFailure() {
        PagedUpstream upstream = new PagedUpstream(Map.of());
        ObservationAdapter adapter = composed(upstream, iot(COMPANY));
        Map<String, Object> payload = ledger(adapter, Map.of("type", "water", "limit", "20"));
        assertThat(items(payload)).isEmpty();
        assertThat(payload.get("coverageComplete")).isEqualTo(true);
        assertThat(payload.get("nextCursor")).isNull();
        assertThat(payload.get("scanned")).isEqualTo(0);
        assertThat(payload.get("excludedCount")).isEqualTo(0);
        assertThat(upstream.reads.get()).isZero();
    }

    @Test
    void currentDeviceEnrichmentUsesAuthorizedHubBindingAndFailsHonest() {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("a1", "gas-1", "2026-10-16T09:00:00Z"), alarm("a2", "gas-2", "2026-10-16T08:00:00Z"))));
        IotAdapter iot = iot(COMPANY, device("gas-1", "27楼茶水间燃气感应器", "hub-1"),
                device("gas-2", "26楼燃气感应器", "hub-2"));
        ObservationAdapter adapter = ObservationAdapterFactory.safetyLedger(upstream, iot,
                (identity, hubDeviceId) -> "hub-1".equals(hubDeviceId)
                        ? Mono.just(current("27楼茶水间燃气感应器（新）", "27楼茶水间"))
                        : Mono.error(new IllegalStateException("detail unavailable")), CLOCK);

        Map<String, Object> payload = adapter.read(request(Map.of("type", "gas", "limit", "20"))).block().payload();
        List<Map<String, Object>> rows = items(payload);
        assertThat(rows).extracting(row -> row.get("eventId")).containsExactly("a1", "a2");
        assertThat(rows.get(0)).containsEntry("deviceName", "27楼茶水间燃气感应器（新）")
                .containsEntry("recordedDeviceName", "27楼茶水间燃气感应器")
                .containsEntry("location", "27楼茶水间");
        assertThat(currentDevice(rows.get(0)))
                .containsEntry("name", "27楼茶水间燃气感应器（新）").containsEntry("location", "27楼茶水间");
        // A failing detail read stays an unverified device, never a ledger failure.
        assertThat(rows.get(1)).containsEntry("deviceName", rows.get(1).get("recordedDeviceName"))
                .containsEntry("location", "");
        assertThat(currentDevice(rows.get(1)))
                .containsEntry("name", null).containsEntry("location", null);
    }

    @Test
    void officeLedgerFiltersByOwnScopeAndCursorsNeverCrossSources() {
        PagedUpstream upstream = new PagedUpstream(Map.of(
                0, List.of(alarm("o1", "smoke-1", "2026-10-16T09:00:00Z"),
                        alarm("c1", "company-smoke-1", "2026-10-16T08:00:00Z"))));
        // Same authorization and tenant as COMPANY: identical owner key, different source.
        // Both composers share one MAC key so only the source binding can reject a replay.
        RequestIdentity office = new RequestIdentity("Bearer company", "TENANT-1", IotSource.OFFICE);
        byte[] macKey = new byte[32];
        ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer officeLedger =
                new ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer(upstream,
                        sourceIot(office, IotSource.OFFICE, device("smoke-1", "27楼光电感烟探测器", null)),
                        null, macKey, CLOCK);
        ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer companyLedger =
                new ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer(upstream,
                        sourceIot(COMPANY, IotSource.COMPANY, device("company-smoke-1", "公司感烟", null)),
                        null, macKey, CLOCK);

        Map<String, Object> payload = officeLedger.readLedger(new ObservationRequest(office,
                ObservationOperation.SAFETY_EVENTS, Map.of("type", "smoke", "limit", "1"))).block().payload();
        assertThat(payload.get("source")).isEqualTo("office");
        assertThat(items(payload)).extracting(row -> row.get("eventId")).containsExactly("o1");
        assertThat(payload.get("excludedCount")).isEqualTo(1);
        assertThat(payload.get("nextCursor")).asString().isNotBlank();

        // An Office continuation can never resume under the Company authorization of
        // the same owner, and Office cannot resolve event details at all.
        assertThat(catchBusiness(() -> companyLedger.readLedger(new ObservationRequest(COMPANY,
                ObservationOperation.SAFETY_EVENTS,
                Map.of("type", "smoke", "limit", "1", "cursor", (String) payload.get("nextCursor")))).block())
                .code()).isEqualTo("INVALID_SAFETY_CURSOR");
        assertThat(catchBusiness(() -> officeLedger.readEvent(new ObservationRequest(office,
                ObservationOperation.SAFETY_EVENT,
                Map.of("id", "o1", "ledger", "smoke", "deviceId", "smoke-1"))))
                .code()).isEqualTo("SAFETY_SOURCE_UNAVAILABLE");
    }

    @Test
    void invalidQueriesFailClosedBeforeAnyUpstreamRead() {
        PagedUpstream upstream = new PagedUpstream(Map.of());
        ObservationAdapter adapter = composed(upstream, iot(COMPANY, device("gas-1", "燃气感应器", null)));
        assertThat(catchBusiness(() -> adapter.read(request(Map.of("type", "fire", "limit", "20"))).block()).code())
                .isEqualTo("INVALID_SAFETY_QUERY");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of("type", "gas", "limit", "0"))).block()).code())
                .isEqualTo("INVALID_SAFETY_QUERY");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of("type", "gas", "limit", "20",
                "from", "2026-10-13", "to", TO))).block()).code()).isEqualTo("INVALID_SAFETY_QUERY");
        assertThat(catchBusiness(() -> adapter.read(request(Map.of("type", "gas", "limit", "20",
                "from", "2026-10-16T00:00:00Z", "to", "2026-10-13T00:00:00Z"))).block()).code())
                .isEqualTo("INVALID_SAFETY_QUERY");
        assertThat(upstream.reads.get()).isZero();
    }

    private static Map<String, Object> ledger(ObservationAdapter adapter, Map<String, String> parameters) {
        return adapter.read(request(parameters)).block().payload();
    }

    private static ObservationRequest request(Map<String, String> parameters) {
        return new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENTS, parameters);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> currentDevice(Map<String, Object> row) {
        return (Map<String, Object>) row.get("currentDevice");
    }

    private static BusinessException catchBusiness(ThrowingCall call) {
        try {
            call.run();
            throw new AssertionError("expected a business failure");
        } catch (BusinessException error) {
            return error;
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("items");
    }

    private static List<String> ids(Map<String, Object> payload) {
        return items(payload).stream().map(row -> (String) row.get("eventId")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> window(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("window");
    }

    private static Map<String, Object> alarm(String id, String deviceId, String occurredAt) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventId", id);
        record.put("ledger", "gas");
        record.put("deviceId", deviceId);
        record.put("deviceName", "27楼茶水间燃气感应器");
        record.put("occurredAt", occurredAt);
        record.put("alarm", true);
        record.put("report", false);
        return record;
    }

    private static Map<String, Object> current(String name, String location) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("location", location);
        return value;
    }

    private static ObservationAdapter composed(ObservationAdapter upstream, IotAdapter iot) {
        return ObservationAdapterFactory.composed(upstream, iot, identity -> Mono.just(Map.of()), null, CLOCK);
    }

    private static IotAdapter iot(RequestIdentity identity, AuthorizedDevice... devices) {
        return new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
                return Mono.just(AuthorizedDeviceScope.of(identity, IotSource.COMPANY, List.of(devices)));
            }

            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity request) {
                return Mono.just(AuthorizedDeviceScope.of(identity, IotSource.COMPANY, List.of(devices)));
            }
        };
    }

    private static IotAdapter sourceIot(RequestIdentity identity, IotSource source, AuthorizedDevice... devices) {
        return new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
                return Mono.just(AuthorizedDeviceScope.of(identity, source, List.of(devices)));
            }

            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity request) {
                return Mono.just(AuthorizedDeviceScope.of(identity, source, List.of(devices)));
            }
        };
    }

    private static AuthorizedDevice device(String id, String name, String hub) {
        return new AuthorizedDevice(id, "safety", name, "", true, null, id, hub, null, List.of(), List.of(), Map.of());
    }

    /** Raw ledger-page double; it records requested pages and read counts. */
    private static final class PagedUpstream implements ObservationAdapter {
        private final Map<Integer, List<Map<String, Object>>> pages;
        private final AtomicInteger reads = new AtomicInteger();
        private final List<String> requestedPages = new ArrayList<>();

        private PagedUpstream(Map<Integer, List<Map<String, Object>>> pages) {
            this.pages = pages;
        }

        @Override
        public Mono<ObservationResponse> read(ObservationRequest request) {
            if (request.operation() != ObservationOperation.SAFETY_EVENTS) {
                return Mono.error(new AssertionError("unexpected operation: " + request.operation()));
            }
            reads.incrementAndGet();
            int page = Integer.parseInt(request.parameters().getOrDefault("page", "0"));
            requestedPages.add(request.parameters().getOrDefault("ledger", "gas") + ":" + page);
            List<Map<String, Object>> items = pages.getOrDefault(page, List.of());
            int last = pages.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "company");
            payload.put("items", items);
            payload.put("total", null);
            payload.put("scanned", items.size());
            payload.put("last", page >= last);
            return Mono.just(ObservationResponse.available(payload));
        }
    }
}
