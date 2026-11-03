package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.RequestIdentity;

/**
 * Auth-bound safety ledger history for read checkpoints with safety devices.
 * Alarm facts come from the real ledger window through the authorized safety
 * scope; a checkpoint must never infer alarming from current device state.
 */
public interface TaskSafetyLedgerReader {

    /**
     * One ledger-type page for {@code [from, to]}. Records are already filtered
     * to the current authorized safety scope and the requested window.
     *
     * @param ledgerType one of smoke, gas, water
     * @param cursor opaque continuation from a previous page, null for a fresh scan
     */
    Page read(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor);

    /** A ledger alarm fact; {@code evidence} carries the upstream record as-is. */
    record Alert(String eventId, String deviceId, String subtype, String occurredAt,
                 Map<String, Object> evidence) {
    }

    /**
     * @param coverageComplete true only when the bounded scan ended; false means
     *                         more records may exist behind {@code nextCursor}
     * @param nextCursor       continuation, null when the scan ended
     */
    record Page(List<Alert> alerts, boolean coverageComplete, String nextCursor) {
        public Page {
            alerts = alerts == null ? List.of() : List.copyOf(alerts);
        }
    }
}
