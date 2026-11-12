package com.ehe.iotagent.audit;

import java.time.Instant;
import java.util.Set;

/**
 * Bounded read for the admin audit list. Identity, source, event type and
 * result filters are pushed down so pagination never loads tenant history
 * into memory. Pages walk the stable (create_time, id) ordering backwards:
 * a cursor names the last row already served and the next page returns
 * strictly older rows, so inserts after the cursor cannot shift or
 * duplicate entries.
 *
 * @param eventType     exact {@code event_type} match chosen by the admin;
 *                      applied in SQL before pagination, distinct from the
 *                      result-derived event type sets below
 * @param statuses       accepted raw payload {@code $.status} values; the raw
 *                      engine terminals (run.completed, ...) appear here as-is
 * @param eventTypes     accepted {@code event_type} values for events without
 *                      a payload status
 * @param excludeEventTypes when true, {@code eventTypes} is inverted: the event
 *                      must NOT have any of those types (used to express the
 *                      UNKNOWN result honestly)
 * @param cursorAt       cursor timestamp, nullable for the first page
 * @param cursorId       cursor row id, only read when {@code cursorAt} is set
 */
public record AuditEventQuery(
        String tenantCode,
        String identityRealm,
        String principalId,
        String source,
        String eventType,
        boolean unresolvedOnly,
        Instant fromAt,
        Instant toAt,
        Set<String> statuses,
        Set<String> eventTypes,
        boolean excludeEventTypes,
        Instant cursorAt,
        long cursorId,
        int limit) {

    public AuditEventQuery {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("tenantCode is required");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        eventType = eventType == null || eventType.isBlank() ? null : eventType.strip();
        if (eventType != null && eventType.length() > 128) {
            throw new IllegalArgumentException("eventType is too long");
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        eventTypes = eventTypes == null ? Set.of() : Set.copyOf(eventTypes);
    }
}
