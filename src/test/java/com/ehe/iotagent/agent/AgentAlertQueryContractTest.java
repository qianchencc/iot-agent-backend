package com.ehe.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ehe.iotagent.action.ActionDraftFacade;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.conversation.AgentToolContext;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationResponse;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskProjectionStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentAlertQueryContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T00:07:59.123456789Z"), ZoneOffset.UTC);

    @Test
    void defaultWindowRemainsValidAndFixedWhileContinuingPastAnEmptyPage() {
        Map<String, String> firstWindow = new LinkedHashMap<>();
        ObservationAdapter observations = query -> {
            if (!query.parameters().containsKey("cursor")) {
                firstWindow.put("from", query.parameters().get("from"));
                firstWindow.put("to", query.parameters().get("to"));
                return Mono.just(page(List.of(), false, "next-water"));
            }
            if (!firstWindow.get("from").equals(query.parameters().get("from"))
                    || !firstWindow.get("to").equals(query.parameters().get("to"))) {
                return Mono.error(new BusinessException("INVALID_CURSOR", "续页窗口变化", 400));
            }
            return Mono.just(page(List.of(alert("water", "event-1", "sensor-1", 1, false)), true, null));
        };

        Map<String, Object> result = query(observations, Map.of("types", List.of("water")));

        assertThat(result).containsEntry("summaryStatus", "complete").containsEntry("coverageComplete", true)
                .containsEntry("total", 1).containsEntry("windowDays", 30);
        assertThat((Map<?, ?>) result.get("window")).isEqualTo(Map.of(
                "from", "2026-10-12T00:07:59.123Z", "to", "2026-11-11T00:07:59.123Z", "timezone", "Asia/Shanghai"));
        assertThat(rows(result.get("records"))).extracting(row -> row.get("eventId")).containsExactly("event-1");
        assertThat(result).doesNotContainKeys("items", "nextCursor");
    }

    @Test
    void countsFilteredUniqueEventsAcrossPagesBeforeLimitingVisibleRecords() {
        Map<String, Object> shared = alert("gas", "shared", "sensor-1", 1, false);
        List<Map<String, Object>> continuation = new ArrayList<>(List.of(
                shared, alert("gas", "shared", "sensor-2", 2, false)));
        for (int index = 3; index <= 12; index++) {
            continuation.add(alert("gas", "event-" + index, "sensor-1", index, false));
        }
        ObservationAdapter observations = query -> Mono.just(query.parameters().containsKey("cursor")
                ? page(continuation, true, null)
                : page(List.of(shared, alert("gas", "reported", "sensor-1", 13, true)), false, "next-gas"));

        Map<String, Object> result = query(observations, Map.of("types", List.of("gas"), "unreportedOnly", true));

        assertThat(result).containsEntry("total", 12).containsEntry("uniqueDevices", 2)
                .containsEntry("coverageComplete", true);
        assertThat(rows(result.get("records"))).hasSize(8)
                .extracting(row -> row.get("eventId")).containsExactly(
                        "event-12", "event-11", "event-10", "event-9", "event-8", "event-7", "event-6", "event-5");
        assertThat(rows(result.get("byType"))).singleElement().satisfies(type -> {
            assertThat(type.get("total")).isEqualTo(12);
            assertThat(type.get("deviceCount")).isEqualTo(2);
        });
        assertThat(rows(result.get("byDevice"))).extracting(row -> row.get("total")).containsExactly(11, 1);
    }

    @Test
    void continuationFailureRetainsKnownRecordsWithoutClaimingCompleteCoverage() {
        ObservationAdapter observations = query -> query.parameters().containsKey("cursor")
                ? Mono.error(new BusinessException("UPSTREAM_UNAVAILABLE", "台账续页读取失败", 503))
                : Mono.just(page(List.of(alert("water", "event-1", "sensor-1", 1, false)), false, "next-water"));

        Map<String, Object> result = query(observations, Map.of("types", List.of("water")));

        assertThat(result).containsEntry("summaryStatus", "partial").containsEntry("coverageComplete", false)
                .containsEntry("total", 1);
        assertThat(rows(result.get("records"))).extracting(row -> row.get("eventId")).containsExactly("event-1");
        assertThat(rows(result.get("failures"))).extracting(row -> row.get("type")).containsExactly("water");
    }

    @Test
    void failedCategoryIsNotReportedAsZeroAndDoesNotDiscardOtherCategories() {
        ObservationAdapter observations = query -> switch (query.parameters().get("type")) {
            case "gas" -> Mono.error(new BusinessException("UPSTREAM_UNAVAILABLE", "燃气台账读取失败", 503));
            case "water" -> Mono.just(page(List.of(alert("water", "event-1", "sensor-1", 1, false)), true, null));
            default -> Mono.just(page(List.of(), true, null));
        };

        Map<String, Object> result = query(observations, Map.of());

        assertThat(result).containsEntry("summaryStatus", "partial").containsEntry("coverageComplete", false)
                .containsEntry("total", 1);
        assertThat(rows(result.get("byType"))).extracting(row -> row.get("type")).containsExactly("smoke", "water");
        assertThat(rows(result.get("byType"))).extracting(row -> row.get("total")).containsExactly(0, 1);
        assertThat(rows(result.get("failures"))).extracting(row -> row.get("type")).containsExactly("gas");
    }

    @Test
    void unavailableQueryHasNoFabricatedZeroCount() {
        ObservationAdapter observations = query -> Mono.error(new BusinessException(
                "UPSTREAM_UNAVAILABLE", "台账读取失败", 503));

        Map<String, Object> result = query(observations, Map.of());

        assertThat(result).containsEntry("summaryStatus", "unavailable")
                .containsEntry("coverageComplete", false).containsEntry("total", null);
        assertThat(rows(result.get("byType"))).isEmpty();
        assertThat(rows(result.get("failures"))).extracting(row -> row.get("type"))
                .containsExactly("smoke", "gas", "water");
    }

    @Test
    void completePaginationDoesNotHideStalePagesFromTheModel() {
        Map<String, Object> stale = Map.of("status", "stale", "updatedAt", "2026-11-11T00:00:00Z",
                "lastError", "安全台账同步未完成");
        Map<String, Object> fresh = Map.of("status", "fresh", "updatedAt", "2026-11-11T00:07:50Z");
        ObservationAdapter observations = request -> {
            String cursor = request.parameters().get("cursor");
            String next = cursor == null ? "stale-page" : "stale-page".equals(cursor) ? "fresh-page" : null;
            Map<String, Object> body = new LinkedHashMap<>(page(List.of(), next == null, next).payload());
            body.put("cache", "stale-page".equals(cursor) ? stale : fresh);
            return Mono.just(ObservationResponse.available(body));
        };

        Map<String, Object> result = query(observations, Map.of("types", List.of("water")));

        assertThat(result).containsEntry("coverageComplete", true).containsEntry("summaryStatus", "complete");
        Map<?, ?> model = (Map<?, ?>) result.get("modelResult");
        assertThat(rows(model.get("byType"))).singleElement()
                .satisfies(type -> assertThat(type).containsEntry("cache", stale));
    }

    @Test
    void missingContinuationCannotBecomeCompleteEmpty() {
        Map<String, Object> result = query(request -> Mono.just(page(List.of(), false, null)),
                Map.of("types", List.of("water")));

        assertThat(result).containsEntry("summaryStatus", "partial").containsEntry("coverageComplete", false);
        assertThat(rows(result.get("failures"))).extracting(row -> row.get("type")).containsExactly("water");
    }

    private Map<String, Object> query(ObservationAdapter observations, Map<String, Object> arguments) {
        ConversationFacade conversations = mock(ConversationFacade.class);
        PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(principal, "run-1", "conversation-1", "turn-1", "company", null, Map.of()));
        AgentToolBridge bridge = AgentBusinessEngineFactory.toolBridge(conversations, mock(IotAdapter.class), observations,
                mock(TaskProjectionStore.class), mock(ActionDraftFacade.class), mock(TaskFacade.class),
                new ObjectMapper(), null, null, CLOCK, com.ehe.iotagent.allowlist.PhoneAllowlist.failClosed("fixture"));
        return bridge.execute(Map.of("runId", "run-1", "toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolCallId", "call-1", "toolName", "query_alerts", "arguments", arguments));
    }

    private static ObservationResponse page(List<Map<String, Object>> records, boolean complete, String cursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", records);
        body.put("total", null);
        body.put("coverageComplete", complete);
        body.put("nextCursor", cursor);
        body.put("scanned", records.isEmpty() ? 300 : records.size());
        return ObservationResponse.available(body);
    }

    private static Map<String, Object> alert(String type, String eventId, String deviceId, int minute, boolean report) {
        return Map.of("eventId", eventId, "ledger", type, "deviceId", deviceId, "deviceName", "传感器 " + deviceId,
                "location", "测试区域", "subtype", type, "occurredAt", "2026-11-10T12:" + "%02d".formatted(minute) + ":00Z",
                "report", report);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
