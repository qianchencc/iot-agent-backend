package ltd.qianc.iotagent.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.internal.SnapshotReadModel;

/** Rebuildable HTTP read projections. Reads never perform storage/upstream I/O; explicit credential operations may block on the durable barrier. */
public interface ReadSnapshots extends AutoCloseable {
    /** Blocking rebuildable storage port; invoked exclusively on the bounded storage executor. */
    interface Persistence {
        java.util.Optional<String> get(String key);
        void put(String key, String value, Duration retention);
        void delete(String key);
    }

    /**
     * Durable cross-process credential-generation barrier. It never stores the credential itself.
     * Implementations scope writes by tenant, credential hash and the gateway namespace.
     * A barrier write must complete before an invalidation or re-admission is reported as done.
     */
    interface Barrier {
        record Key(String tenantCode, String credentialHash) {
            public Key {
                Objects.requireNonNull(tenantCode);
                Objects.requireNonNull(credentialHash);
            }
            @Override public String toString() { return "BarrierKey[redacted]"; }
        }
        record State(String generation, Instant until, int denialStatus, Instant issuedAt) {
            public State {
                Objects.requireNonNull(generation);
                Objects.requireNonNull(until);
                Objects.requireNonNull(issuedAt);
                if (generation.isBlank() || denialStatus != 0 && denialStatus != 401 && denialStatus != 403) {
                    throw new IllegalArgumentException("Invalid snapshot credential barrier");
                }
            }
        }
        /** Whether the previous instance stopped cleanly and the epoch that tags restore-safe bodies. */
        record Checkpoint(boolean recoverySafe, String epoch) {
            public Checkpoint {
                Objects.requireNonNull(epoch);
                if (epoch.isBlank()) throw new IllegalArgumentException("Invalid recovery epoch");
            }
        }
        /**
         * Atomically records this process as dirty and returns whether the previous instance was clean.
         * An unclean start must rotate the epoch so no older body can be restored later either.
         */
        Checkpoint startInstance(String instanceId);
        /** Marks clean only while this exact instance still owns the checkpoint. */
        boolean markClean(String instanceId);
        java.util.Optional<State> read(Key key);
        /**
         * Publishes a transition only if it was issued no earlier than the stored one. A retried
         * old invalidation therefore cannot overwrite a later admission. Returns whether it applied.
         */
        boolean write(Key key, State state);
        /**
         * Extends an existing generation only while it is still current and not newer. A null
         * expectation means only create when absent. A stale worker must never replace a newer generation.
         */
        boolean writeIfCurrent(Key key, String expectedGeneration, State state);
    }

    record Identity(String tenantCode, String authorization, String source, String ownerKey) {
        public Identity {
            Objects.requireNonNull(tenantCode);
            Objects.requireNonNull(authorization);
            Objects.requireNonNull(source);
            Objects.requireNonNull(ownerKey);
        }
        @Override public String toString() { return "SnapshotIdentity[redacted]"; }
    }

    enum Policy {
        CURRENT, HISTORICAL;
        private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
        public Instant nextRefresh(Instant updatedAt) {
            return this == CURRENT ? updatedAt.plusSeconds(60)
                    : updatedAt.atZone(SHANGHAI).toLocalDate().plusDays(1).atStartOfDay(SHANGHAI).toInstant();
        }
        /** Only an explicitly closed past window is historical; mixed/current/unknown windows stay current. */
        public static Policy forQuery(Map<String, ?> query, Clock clock) {
            if (query.containsKey("weeks") && !query.containsKey("endDate")) return CURRENT;
            LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
            for (String key : new String[]{"from", "to", "windowFrom", "windowTo", "baselineFrom", "baselineTo",
                    "baseFrom", "baseTo", "endDate", "end", "date"}) {
                Object value = query.get(key);
                if (value instanceof String text && text.length() >= 10) {
                    try { if (!LocalDate.parse(text.substring(0, 10)).isBefore(today)) return CURRENT; }
                    catch (RuntimeException ignored) { return CURRENT; }
                }
            }
            for (String key : new String[]{"to", "windowTo", "endDate", "end", "date"}) {
                Object value = query.get(key);
                if (value instanceof String text && text.length() >= 10) {
                    try { return LocalDate.parse(text.substring(0, 10)).isBefore(today) ? HISTORICAL : CURRENT; }
                    catch (RuntimeException ignored) { return CURRENT; }
                }
            }
            Object month = query.get("endMonth");
            if (month instanceof String text) {
                try { return YearMonth.parse(text).isBefore(YearMonth.from(today)) ? HISTORICAL : CURRENT; }
                catch (RuntimeException ignored) { return CURRENT; }
            }
            return CURRENT;
        }
    }

    record Settings(int maximumEntries, Duration idleExpiry, Duration retention, Duration retryDelay) {
        public Settings {
            if (maximumEntries < 1 || idleExpiry.isNegative() || idleExpiry.isZero()
                    || retention.isNegative() || retention.isZero() || retryDelay.compareTo(Duration.ofSeconds(60)) < 0) {
                throw new IllegalArgumentException("Invalid snapshot settings");
            }
        }
        public static Settings defaults() { return new Settings(2048, Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(60)); }
    }

    Map<String, Object> read(Identity identity, String realm, Map<String, ?> fullTypedQuery,
            Policy policy, Supplier<Map<String, Object>> loader);

    /**
     * Blocking background composition seam. Unlike {@link #read}, a miss is loaded on
     * the calling thread, so snapshot loaders can compose inner date blocks without
     * occupying the snapshot worker twice. A held body returns immediately, including
     * while due or refreshing; {@link #refreshActive} owns its background renewal.
     * Credential generations and persistence remain shared with {@link #read}, which
     * ordinary HTTP must continue to use. Failed renewal never replaces a good body.
     */
    Map<String, Object> load(Identity identity, String realm, Map<String, ?> fullTypedQuery,
            Policy policy, Supplier<Map<String, Object>> loader);
    /** Marks held credential projections stale and replaces their generation, without clearing visible data. */
    void invalidate(Identity identity);
    /** Discards credential projections and their persisted generation without denying a later newly verified read. */
    void discard(Identity identity);
    /** Clears all held credential projections and denies subsequent reads; only 401/403 are accepted. */
    void revoke(Identity identity, BusinessException failure);
    /**
     * Confirms a genuine re-verification. Only an existing old denial and its projections are cleared;
     * a credential without a recorded denial is left untouched. Fails if the durable barrier cannot be updated.
     */
    void admit(Identity identity);
    void refreshActive();
    /** Marks the recovery checkpoint clean only when every barrier and queued transition drained. */
    void close(boolean drainComplete);
    @Override default void close() { close(true); }

    /**
     * Executors must enqueue work, never run it inline; storage execution must be serial. The caller owns their lifecycle.
     * Without a barrier the model keeps serving and refreshing memory but never restores or persists a Redis body.
     */
    static ReadSnapshots create(Clock clock, Executor upstreamExecutor, Executor storageExecutor,
            Persistence store, Barrier barrier, ObjectMapper mapper, Settings settings) {
        return new SnapshotReadModel(clock, upstreamExecutor, storageExecutor, store, barrier, mapper, settings);
    }
}
