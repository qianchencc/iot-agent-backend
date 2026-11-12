package com.ehe.iotagent.task.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.task.TaskSafetyLedgerReader;

/**
 * Adapts the composed public observation boundary to the task safety seam. The
 * composed adapter performs scope filtering, window filtering, coverage
 * accounting and opaque owner-bound continuation; this class only blocks the
 * reactive call for the synchronous gateway and maps the payload facts. A
 * tampered or expired cursor restarts the scan once instead of failing the
 * checkpoint.
 */
public final class ObservationSafetyLedgerReader implements TaskSafetyLedgerReader {
    private static final String PAGE_LIMIT = "100";
    private final ObservationAdapter upstream;
    private final Duration timeout;

    public ObservationSafetyLedgerReader(ObservationAdapter upstream, Duration timeout) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.timeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(30) : timeout;
    }

    @Override
    public Page read(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ledgerType, "ledgerType");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        String continuation = cursor == null || cursor.isBlank() ? null : cursor;
        try {
            return readPage(identity, ledgerType, from, to, continuation);
        } catch (RuntimeException failure) {
            if (continuation != null && isCursorInvalid(failure)) {
                // The owner-bound cursor expired between checkpoints: restart the
                // window scan from the first page rather than failing the read.
                return readPage(identity, ledgerType, from, to, null);
            }
            throw failure;
        }
    }

    private Page readPage(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("type", ledgerType);
        parameters.put("from", from.toString());
        parameters.put("to", to.toString());
        parameters.put("limit", PAGE_LIMIT);
        if (cursor != null) parameters.put("cursor", cursor);
        ObservationRequest request = new ObservationRequest(identity, ObservationOperationEnum.SAFETY_EVENTS, parameters);
        Map<String, Object> payload;
        try {
            payload = upstream.read(request).block(timeout).payload();
        } catch (RuntimeException failure) {
            if (isCursorInvalid(failure)) throw failure;
            throw new BusinessException("SAFETY_LEDGER_READ_FAILED", "安全台账读取失败", 503);
        }
        List<Alert> alerts = payload.get("items") instanceof List<?> items
                ? items.stream()
                        .map(item -> item instanceof Map<?, ?> map ? alert(map) : null)
                        .filter(java.util.Objects::nonNull)
                        .toList()
                : List.of();
        boolean coverageComplete = Boolean.TRUE.equals(payload.get("coverageComplete"));
        Object next = payload.get("nextCursor");
        String nextCursor = next == null ? null : String.valueOf(next);
        if (nextCursor == null || nextCursor.isBlank() || "null".equals(nextCursor)) nextCursor = null;
        return new Page(alerts, coverageComplete, nextCursor);
    }

    private static Alert alert(Map<?, ?> item) {
        String eventId = text(item.get("eventId"));
        String deviceId = text(item.get("deviceId"));
        String occurredAt = text(item.get("occurredAt"));
        if (eventId == null || deviceId == null || occurredAt == null) return null;
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : item.entrySet()) {
            evidence.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new Alert(eventId, deviceId, text(item.get("subtype")), occurredAt, evidence);
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static boolean isCursorInvalid(RuntimeException failure) {
        return failure instanceof BusinessException business
                && "INVALID_SAFETY_CURSOR".equals(business.code());
    }
}
