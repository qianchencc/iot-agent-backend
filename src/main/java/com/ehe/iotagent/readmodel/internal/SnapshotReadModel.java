package com.ehe.iotagent.readmodel.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.readmodel.ReadSnapshots;

/** All entry state is guarded by this monitor; external work always runs outside it. */
public final class SnapshotReadModel implements ReadSnapshots {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final Clock clock;
    private final Executor upstreamExecutor;
    private final Executor storageExecutor;
    private final Persistence store;
    private final Barrier barrier;
    private final ObjectMapper mapper;
    private final Settings settings;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Revocation> revoked = new LinkedHashMap<>();
    private final Map<String, PendingDenial> pendingDenials = new LinkedHashMap<>();
    private final Map<String, Marker> invalidated = new LinkedHashMap<>();
    private final java.util.Set<Marker> unsettled = new java.util.LinkedHashSet<>();
    // Serializes durable generation writes against the background snapshot persistence job.
    private final Object barrierWrites = new Object();
    private final String instanceId;
    private final String recoveryEpoch;
    private Instant restoreDisabledUntil = Instant.MIN;
    private boolean revocationSaturated;
    private boolean closed;

    public SnapshotReadModel(Clock clock, Executor upstreamExecutor, Executor storageExecutor,
            Persistence store, Barrier barrier, ObjectMapper mapper, Settings settings) {
        this.clock = clock;
        this.upstreamExecutor = upstreamExecutor;
        this.storageExecutor = storageExecutor;
        this.store = store;
        this.barrier = barrier;
        this.mapper = mapper;
        this.settings = settings;
        this.instanceId = java.util.UUID.randomUUID().toString();
        Barrier.Checkpoint checkpoint = barrier == null ? null : barrier.startInstance(instanceId);
        this.recoveryEpoch = checkpoint == null ? null : checkpoint.epoch();
    }

    @Override public Map<String, Object> read(Identity identity, String realm, Map<String, ?> query,
            PolicyEnum policy, Supplier<Map<String, Object>> loader) {
        String credential = credential(identity);
        String key = key(identity, realm, query);
        Entry entry;
        boolean restore = false;
        boolean load = false;
        synchronized (this) {
            Instant now = clock.instant();
            expire(now);
            checkCredentialState(credential);
            if (revocationSaturated) return unavailable("SNAPSHOT_CAPACITY", "数据同步繁忙，请稍后重试");
            if (closed) return unavailable("SNAPSHOT_STOPPED", "数据同步暂不可用");
            entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= settings.maximumEntries()) return unavailable("SNAPSHOT_CAPACITY", "数据同步繁忙，请稍后重试");
                entry = new Entry(key, credential, identity.tenantCode(), policy, loader, now);
                entries.put(key, entry);
                entry.busy = true;
                restore = store != null && barrier != null && recoveryEpoch != null
                        && !now.isBefore(restoreDisabledUntil) && !restoreBlocked(credential);
                entry.restoring = restore;
                entry.restoreUntil = now.plusSeconds(2);
                load = !restore;
            } else {
                entry.lastAccess = now;
                entry.loader = loader;
                if (entry.policy != policy) {
                    entry.policy = policy;
                    if (entry.updated != null && entry.error == null && !entry.forcedStale) entry.due = policy.nextRefresh(entry.updated);
                }
                if (entry.terminal != null) throw entry.terminal;
                if (!entry.busy && entry.activeLoads == 0 && !now.isBefore(entry.due)) {
                    entry.busy = true;
                    load = true;
                }
            }
        }
        if (restore) restore(entry);
        else if (load) submit(entry);
        synchronized (this) {
            checkCredentialState(credential);
            if (entry.terminal != null) throw entry.terminal;
            if (!active(entry)) return unavailable("SNAPSHOT_INVALIDATED", "数据已更新，请重新读取");
            return view(entry);
        }
    }

    @Override
    public Map<String, Object> load(Identity identity, String realm, Map<String, ?> query,
            PolicyEnum policy, Supplier<Map<String, Object>> loader) {
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(loader, "loader");
        String credential = credential(identity);
        String key = key(identity, realm, query);
        Entry entry;
        synchronized (this) {
            Instant now = clock.instant();
            expire(now);
            checkCredentialState(credential);
            if (revocationSaturated) throw new BusinessException("SNAPSHOT_CAPACITY", "数据同步繁忙，请稍后重试", 503);
            if (closed) throw new BusinessException("SNAPSHOT_STOPPED", "数据同步暂不可用", 503);
            entry = entries.get(key);
            if (entry == null) {
                if (entries.size() >= settings.maximumEntries()) {
                    throw new BusinessException("SNAPSHOT_CAPACITY", "数据同步繁忙，请稍后重试", 503);
                }
                entry = new Entry(key, credential, identity.tenantCode(), policy, loader, now);
                entries.put(key, entry);
            } else {
                entry.lastAccess = now;
                entry.loader = loader;
                if (entry.policy != policy) {
                    entry.policy = policy;
                    if (entry.updated != null && entry.error == null && !entry.forcedStale) {
                        entry.due = policy.nextRefresh(entry.updated);
                    }
                }
                if (entry.terminal != null) throw entry.terminal;
            }
            // Composition consumes the held backend value, not the completion of a
            // due or already-running upstream refresh. refreshActive owns that work.
            if (entry.body != null) return new LinkedHashMap<>(entry.body);
            entry.activeLoads++;
        }
        try {
            synchronized (entry.loadLock) {
                synchronized (this) {
                    checkCredentialState(credential);
                    if (!active(entry)) throw new BusinessException("SNAPSHOT_INVALIDATED", "数据已更新，请重新读取", 503);
                    if (entry.body != null) {
                        return new LinkedHashMap<>(entry.body);
                    }
                }
                Restored restored = null;
                if (store != null && barrier != null && recoveryEpoch != null && !restoreBlocked(credential)) {
                    restored = restoreBlocking(entry);
                }
                if (restored != null) {
                    synchronized (this) {
                        if (!active(entry)) throw new BusinessException("SNAPSHOT_INVALIDATED", "数据已更新，请重新读取", 503);
                        entry.body = restored.body();
                        entry.updated = restored.updated();
                        entry.due = entry.policy.nextRefresh(restored.updated());
                        entry.restoring = false;
                        return new LinkedHashMap<>(entry.body);
                    }
                }
                Map<String, Object> body;
                try {
                    body = cleanMap(java.util.Objects.requireNonNull(loader.get(), "Snapshot body"));
                } catch (RuntimeException failure) {
                    failed(entry, failure);
                    throw failure;
                }
                Instant updated = clock.instant();
                synchronized (this) {
                    if (!active(entry)) throw new BusinessException("SNAPSHOT_INVALIDATED", "数据已更新，请重新读取", 503);
                    entry.body = body;
                    entry.updated = updated;
                    entry.due = entry.policy.nextRefresh(updated);
                    entry.error = null;
                    entry.forcedStale = false;
                    entry.busy = false;
                    entry.restoring = false;
                }
                persist(entry, body, updated);
                return new LinkedHashMap<>(body);
            }
        } finally {
            synchronized (this) {
                if (entry.activeLoads > 0) entry.activeLoads--;
            }
        }
    }

    private Restored restoreBlocking(Entry entry) {
        java.util.concurrent.FutureTask<Restored> task = new java.util.concurrent.FutureTask<>(() -> {
            Marker marker = readMarker(entry.credential, entry.tenantCode);
            if (marker == null) return null;
            checkDenial(marker);
            String encoded = store.get(entry.key).orElse(null);
            if (encoded == null) return null;
            Map<String, Object> record = mapper.readValue(encoded, MAP);
            Instant updated = Instant.parse((String) record.get("updatedAt"));
            if (recoveryEpoch.equals(record.get("epoch")) && marker.generation.equals(record.get("generation"))
                    && updated.plus(settings.retention()).isAfter(clock.instant())
                    && !updated.isAfter(clock.instant())
                    && record.get("body") instanceof Map<?, ?> body) {
                return new Restored(cleanMap(body), updated);
            }
            return null;
        });
        try {
            storageExecutor.execute(task);
            return task.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof BusinessException business) throw business;
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (java.util.concurrent.TimeoutException | RejectedExecutionException unavailable) {
            return null;
        }
    }

    /** Must be called with the state monitor held. */
    private void checkCredentialState(String credential) {
        Revocation published = revoked.get(credential);
        if (published != null) throw published.failure;
        PendingDenial pending = pendingDenials.get(credential);
        if (pending != null) throw pending.failure;
    }

    private void restore(Entry entry) {
        try {
            storageExecutor.execute(() -> {
                Map<String, Object> restored = null;
                Instant updated = null;
                BusinessException denial = null;
                try {
                    // An absent or unreadable durable barrier never proves that a stored body is current.
                    Marker marker = readMarker(entry.credential, entry.tenantCode);
                    if (marker != null) {
                        checkDenial(marker);
                        String encoded = store.get(entry.key).orElse(null);
                        if (encoded != null) {
                            Map<String, Object> record = mapper.readValue(encoded, MAP);
                            updated = Instant.parse((String) record.get("updatedAt"));
                            if (recoveryEpoch.equals(record.get("epoch")) && marker.generation.equals(record.get("generation"))
                                    && updated.plus(settings.retention()).isAfter(clock.instant()) && !updated.isAfter(clock.instant())
                                    && record.get("body") instanceof Map<?, ?> body) restored = cleanMap(body);
                        }
                    }
                } catch (BusinessException failure) {
                    denial = failure;
                } catch (Exception ignored) {
                    // A failed tombstone lookup never permits restoration of a possibly invalidated body.
                }
                synchronized (this) {
                    if (!active(entry) || !entry.restoring) return;
                    entry.restoring = false;
                    if (denial == null && restored != null) {
                        entry.body = restored;
                        entry.updated = updated;
                        entry.due = entry.policy.nextRefresh(updated);
                        if (clock.instant().isBefore(entry.due)) { entry.busy = false; return; }
                    }
                }
                if (denial != null) failed(entry, denial);
                else submit(entry);
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (this) { if (!active(entry) || !entry.restoring) return; entry.restoring = false; }
            submit(entry);
        }
    }

    private void submit(Entry entry) {
        try {
            upstreamExecutor.execute(() -> {
                synchronized (this) {
                    if (!active(entry)) return;
                    if (entry.activeLoads > 0) {
                        // An inline background load already owns this entry; it publishes once.
                        entry.busy = false;
                        return;
                    }
                }
                synchronized (entry.loadLock) {
                    Supplier<Map<String, Object>> loader;
                    synchronized (this) {
                        if (!active(entry)) return;
                        if (entry.activeLoads > 0) { entry.busy = false; return; }
                        if (entry.body != null && entry.error == null && !entry.forcedStale
                                && clock.instant().isBefore(entry.due)) {
                            entry.busy = false;
                            return;
                        }
                        loader = entry.loader;
                    }
                    try {
                        Map<String, Object> result = cleanMap(java.util.Objects.requireNonNull(loader.get(), "Snapshot body"));
                        Instant updated = clock.instant();
                        synchronized (this) {
                            if (!active(entry)) return;
                            entry.body = result;
                            entry.updated = updated;
                            entry.due = entry.policy.nextRefresh(updated);
                            entry.error = null;
                            entry.forcedStale = false;
                            entry.busy = false;
                        }
                        persist(entry, result, updated);
                    } catch (RuntimeException failure) { failed(entry, failure); }
                }
            });
        } catch (RejectedExecutionException rejected) { failed(entry, rejected); }
    }

    private void persist(Entry entry, Map<String, Object> body, Instant updated) {
        // Without a durable barrier and recovery epoch a stored body could not be proven current after a restart.
        if (store == null || barrier == null || recoveryEpoch == null) return;
        try {
            storageExecutor.execute(() -> {
                Marker marker;
                Marker durable = null;
                synchronized (barrierWrites) {
                    synchronized (this) {
                        if (!active(entry) || !updated.equals(entry.updated)) return;
                        marker = invalidated.get(entry.credential);
                        if (marker == null && clock.instant().isBefore(restoreDisabledUntil)) return;
                    }
                    try {
                        if (marker == null) marker = readMarker(entry.credential, entry.tenantCode);
                    } catch (RuntimeException unavailable) {
                        return; // A barrier we cannot read cannot authorize a new stored body.
                    }
                    if (marker != null) {
                        try { checkDenial(marker); }
                        catch (BusinessException denial) { failed(entry, denial); return; }
                    }
                    Instant required = updated.plus(settings.retention());
                    Instant horizon = updated.plus(settings.retention().multipliedBy(2));
                    Instant until = marker != null && marker.until.isAfter(horizon) ? marker.until : horizon;
                    durable = marker == null
                            ? new Marker(entry.credential, entry.tenantCode, java.util.UUID.randomUUID().toString(),
                                    clock.instant(), until, 0, false)
                            : new Marker(entry.credential, entry.tenantCode, marker.generation, marker.issuedAt,
                                    until, marker.denialStatus, marker.blocksRestore);
                    boolean needsWrite = marker == null || marker.pending || marker.until.isBefore(required);
                    if (needsWrite) {
                        try {
                            if (!barrier.writeIfCurrent(new Barrier.Key(entry.tenantCode, entry.credential),
                                    marker == null ? null : marker.generation,
                                    new Barrier.State(durable.generation, durable.until, durable.denialStatus,
                                            durable.issuedAt))) {
                                return; // Another process already installed a newer generation.
                            }
                        } catch (RuntimeException unavailable) {
                            return; // Snapshot bodies are never written without a durable generation reference.
                        }
                    }
                    durable.pending = false;
                    synchronized (this) {
                        if (!active(entry) || !updated.equals(entry.updated)) return;
                        Marker current = invalidated.get(entry.credential);
                        if (current != null) {
                            if (!current.generation.equals(durable.generation)) return;
                            current.extend(durable.until);
                            current.pending = false;
                            // A body stored under the replacement generation cannot resurrect an older one.
                            current.blocksRestore = false;
                        } else if (invalidated.size() < settings.maximumEntries()) {
                            invalidated.put(entry.credential, durable);
                        }
                    }
                }
                try {
                    store.put(entry.key, mapper.writeValueAsString(Map.of("body", body, "updatedAt", updated.toString(),
                            "generation", durable.generation, "epoch", recoveryEpoch)), settings.retention());
                    boolean invalid;
                    synchronized (this) { invalid = !active(entry); }
                    if (invalid) store.delete(entry.key);
                } catch (Exception ignored) { /* Failed persistence never damages a held process projection. */ }
            });
        } catch (RejectedExecutionException ignored) { /* Persistence never blocks a held read. */ }
    }

    private void failed(Entry entry, Throwable failure) {
        BusinessException business = businessFailure(failure);
        if (business != null && (business.httpStatus() == 401 || business.httpStatus() == 403)) {
            deny(entry.credential, entry.tenantCode, business, entry, false);
            return;
        }
        synchronized (this) {
            if (!active(entry)) return;
            entry.busy = false;
            entry.due = clock.instant().plus(settings.retryDelay());
            if (business != null && business.httpStatus() >= 400 && business.httpStatus() < 500) {
                entry.terminal = business;
                entry.body = null;
            }
            entry.error = business == null ? Map.of("code", "SNAPSHOT_REFRESH_FAILED", "message", "数据同步失败，请稍后重试")
                    : Map.of("code", business.code(), "message", business.getMessage());
        }
    }

    @Override public void invalidate(Identity identity) {
        String credential = credential(identity);
        List<String> keys = new ArrayList<>();
        Marker marker;
        synchronized (this) {
            marker = markInvalidated(credential, identity.tenantCode(), 0);
            entries.replaceAll((key, old) -> {
                if (!old.credential.equals(credential)) return old;
                old.valid = false;
                keys.add(key);
                Entry replacement = new Entry(key, credential, old.tenantCode, old.policy, old.loader, old.lastAccess);
                replacement.body = old.body;
                replacement.updated = old.updated;
                replacement.forcedStale = true;
                return replacement;
            });
        }
        deletePersisted(keys);
        requireMarker(marker, true);
    }
    @Override public void revoke(Identity identity, BusinessException failure) {
        if (failure.httpStatus() != 401 && failure.httpStatus() != 403) throw new IllegalArgumentException("Expected authorization failure");
        deny(credential(identity), identity.tenantCode(), failure, null, true);
    }
    @Override public void discard(Identity identity) {
        String credential = credential(identity);
        List<String> keys = new ArrayList<>();
        Marker marker;
        PendingDenial previous;
        synchronized (this) {
            marker = markInvalidated(credential, identity.tenantCode(), 0);
            previous = pendingDenials.remove(credential);
            revoked.remove(credential);
            removeEntries(credential, keys);
        }
        deletePersisted(keys);
        if (!requireMarker(marker, true)) {
            throw new IllegalStateException("Snapshot credential barrier superseded");
        }
        if (previous != null) {
            synchronized (this) { unsettled.remove(previous.marker); }
        }
    }
    /**
     * Hides the credential's projections immediately. 401/403 is published only after the durable
     * denial is stored; until then reads observe the pending 503 state. Ordinary reads never wait.
     */
    private void deny(String credential, String tenantCode, BusinessException rejection, Entry expected,
            boolean requireDurable) {
        List<String> keys = new ArrayList<>();
        Marker marker;
        synchronized (this) {
            if (expected != null && !active(expected)) return;
            removeEntries(credential, keys);
            if (revoked.containsKey(credential)) {
                marker = null; // Already published and durable.
            } else {
                PendingDenial pending = pendingDenials.get(credential);
                if (pending != null) {
                    marker = pending.marker;
                } else if (pendingDenials.size() < settings.maximumEntries()) {
                    marker = markInvalidated(credential, tenantCode, rejection.httpStatus());
                    pendingDenials.put(credential, new PendingDenial(rejection, marker,
                            clock.instant().plus(settings.retryDelay())));
                } else {
                    revocationSaturated = true;
                    marker = null;
                }
            }
        }
        deletePersisted(keys);
        if (marker == null) return;
        boolean durable = requireMarker(marker, false);
        if (durable) publishDenial(marker);
        else if (requireDurable) throw new IllegalStateException("Snapshot credential barrier unavailable");
    }
    private void publishDenial(Marker marker) {
        synchronized (this) {
            PendingDenial pending = pendingDenials.get(marker.credential);
            if (pending == null || pending.marker != marker) return;
            pendingDenials.remove(marker.credential);
            if (revoked.size() < settings.maximumEntries() || revoked.containsKey(marker.credential)) {
                revoked.put(marker.credential, new Revocation(pending.rejection, marker.until));
            } else {
                revocationSaturated = true;
            }
        }
    }
    @Override public void admit(Identity identity) {
        String credential = credential(identity);
        String tenantCode = identity.tenantCode();
        List<String> keys = new ArrayList<>();
        Marker cleared;
        if (barrier == null) {
            synchronized (this) {
                if (closed) return;
                boolean denied = revoked.containsKey(credential) || pendingDenials.containsKey(credential)
                        || invalidated.get(credential) != null && invalidated.get(credential).denialStatus != 0;
                if (!denied) return;
                cleared = markInvalidated(credential, tenantCode, 0);
                PendingDenial previous = pendingDenials.remove(credential);
                if (previous != null) unsettled.remove(previous.marker);
                revoked.remove(credential);
                removeEntries(credential, keys);
            }
        } else {
            synchronized (barrierWrites) {
                Marker durable;
                try {
                    durable = readMarker(credential, tenantCode);
                } catch (RuntimeException unavailable) {
                    throw new IllegalStateException("Snapshot credential barrier unavailable", unavailable);
                }
                Revocation observedRevocation;
                PendingDenial observedPending;
                synchronized (this) {
                    if (closed) return;
                    boolean denied = revoked.containsKey(credential) || pendingDenials.containsKey(credential)
                            || invalidated.get(credential) != null && invalidated.get(credential).denialStatus != 0
                            || durable != null && durable.denialStatus != 0;
                    if (!denied) return;
                    observedRevocation = revoked.get(credential);
                    observedPending = pendingDenials.get(credential);
                    cleared = markInvalidated(credential, tenantCode, 0);
                    removeEntries(credential, keys);
                }
                try {
                    if (!writeBarrier(cleared)) {
                        throw new IllegalStateException("Snapshot credential barrier superseded");
                    }
                } catch (RuntimeException unavailable) {
                    throw new IllegalStateException("Snapshot credential barrier unavailable", unavailable);
                }
                synchronized (this) {
                    if (observedRevocation != null && revoked.get(credential) == observedRevocation) {
                        revoked.remove(credential);
                    }
                    if (observedPending != null && pendingDenials.get(credential) == observedPending) {
                        pendingDenials.remove(credential);
                        unsettled.remove(observedPending.marker);
                    }
                }
            }
        }
        deletePersisted(keys);
    }
    private void removeEntries(String credential, List<String> keys) {
        entries.values().removeIf(entry -> {
            if (!entry.credential.equals(credential)) return false;
            entry.valid = false;
            keys.add(entry.key);
            return true;
        });
    }
    private Marker markInvalidated(String credential, String tenantCode, int denialStatus) {
        Instant now = clock.instant();
        Marker marker = new Marker(credential, tenantCode, java.util.UUID.randomUUID().toString(),
                now, now.plus(settings.retention()), denialStatus, true);
        if (invalidated.size() < settings.maximumEntries() || invalidated.containsKey(credential)) {
            invalidated.put(credential, marker);
        } else {
            // Overflow keys are not remembered individually; prohibit restore until all their old bodies must have expired.
            restoreDisabledUntil = marker.until;
        }
        return marker;
    }

    private Marker readMarker(String credential, String tenantCode) {
        if (barrier == null) return null;
        Barrier.State state = barrier.read(new Barrier.Key(tenantCode, credential)).orElse(null);
        if (state == null || !state.until().isAfter(clock.instant())) return null;
        Marker marker = new Marker(credential, tenantCode, state.generation(), state.issuedAt(),
                state.until(), state.denialStatus(), false);
        marker.pending = false;
        return marker;
    }

    private void checkDenial(Marker marker) {
        if (marker.denialStatus != 0) throw new BusinessException("SNAPSHOT_CREDENTIAL_REVOKED", "当前凭据无权读取数据，请重新登录", marker.denialStatus);
    }

    /** Writes the barrier on the calling thread; returns false when the durable state was not applied. */
    private boolean requireMarker(Marker marker, boolean requireDurable) {
        if (barrier == null) {
            synchronized (this) { marker.pending = false; }
            return true;
        }
        synchronized (barrierWrites) {
            synchronized (this) {
                if (closed) return false;
                Marker current = invalidated.get(marker.credential);
                if (current != null && current != marker) {
                    unsettled.remove(marker);
                    return false;
                }
                marker.nextAttempt = clock.instant().plus(settings.retryDelay());
            }
            try {
                if (writeBarrier(marker)) return true;
                synchronized (this) {
                    marker.pending = false; // A newer transition already owns the durable state.
                }
                return false;
            } catch (RuntimeException failure) {
                synchronized (this) {
                    marker.pending = true;
                    marker.nextAttempt = clock.instant().plus(settings.retryDelay());
                    unsettled.add(marker);
                }
                if (requireDurable) throw new IllegalStateException("Snapshot credential barrier unavailable", failure);
                return false;
            }
        }
    }

    private boolean writeBarrier(Marker marker) {
        boolean applied = barrier.write(new Barrier.Key(marker.tenantCode, marker.credential),
                new Barrier.State(marker.generation, marker.until, marker.denialStatus, marker.issuedAt));
        synchronized (this) {
            if (invalidated.get(marker.credential) == marker) marker.pending = false;
            unsettled.remove(marker);
        }
        return applied;
    }

    private void retryMarker(Marker marker) {
        if (barrier == null) return;
        try {
            storageExecutor.execute(() -> {
                synchronized (barrierWrites) {
                    synchronized (this) {
                        if (closed || invalidated.get(marker.credential) != marker) return;
                    }
                    try {
                        if (!writeBarrier(marker)) {
                            synchronized (this) { marker.pending = false; } // Superseded by a newer transition.
                        }
                    } catch (RuntimeException ignored) {
                        /* The next bounded cadence tries again. */
                        synchronized (this) { unsettled.add(marker); }
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (this) { unsettled.add(marker); }
        }
    }

    private void retryPendingDenial(PendingDenial pending) {
        try {
            storageExecutor.execute(() -> {
                synchronized (barrierWrites) {
                    synchronized (this) {
                        if (closed || pendingDenials.get(pending.marker.credential) != pending) return;
                    }
                    boolean applied;
                    try {
                        applied = writeBarrier(pending.marker);
                    } catch (RuntimeException failure) {
                        synchronized (this) {
                            pending.writeInFlight = false;
                            pending.nextAttempt = clock.instant().plus(settings.retryDelay());
                            unsettled.add(pending.marker);
                        }
                        return;
                    }
                    if (!applied) {
                        // A newer transition owns the barrier; adopt it instead of retrying this one.
                        Marker current;
                        try {
                            current = readMarker(pending.marker.credential, pending.marker.tenantCode);
                        } catch (RuntimeException unavailable) {
                            synchronized (this) {
                                pending.writeInFlight = false;
                                pending.nextAttempt = clock.instant().plus(settings.retryDelay());
                            }
                            return;
                        }
                        synchronized (this) {
                            if (pendingDenials.get(pending.marker.credential) != pending) return;
                            pending.writeInFlight = false;
                            pendingDenials.remove(pending.marker.credential);
                            if (current != null && current.denialStatus != 0) {
                                revoked.put(pending.marker.credential, new Revocation(new BusinessException(
                                        "SNAPSHOT_CREDENTIAL_REVOKED", "当前凭据无权读取数据，请重新登录",
                                        current.denialStatus), current.until));
                            }
                        }
                        return;
                    }
                    synchronized (this) { pending.writeInFlight = false; }
                    publishDenial(pending.marker);
                }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (this) {
                pending.writeInFlight = false;
                pending.nextAttempt = clock.instant().plus(settings.retryDelay());
                unsettled.add(pending.marker);
            }
        }
    }

    private void deletePersisted(List<String> keys) {
        if (store != null && !keys.isEmpty()) {
            try { storageExecutor.execute(() -> keys.forEach(key -> { try { store.delete(key); } catch (RuntimeException ignored) { } })); }
            catch (RejectedExecutionException ignored) { /* Generation guards still prevent in-process restoration. */ }
        }
    }

    @Override public void refreshActive() {
        List<Entry> jobs = new ArrayList<>();
        Map<String, Marker> markers = new LinkedHashMap<>();
        List<PendingDenial> denials = new ArrayList<>();
        synchronized (this) {
            if (closed) return;
            Instant now = clock.instant();
            expire(now);
            invalidated.forEach((credential, marker) -> {
                if (marker.pending && !pendingDenials.containsKey(credential) && !now.isBefore(marker.nextAttempt)) {
                    markers.put(credential, marker);
                }
            });
            pendingDenials.values().forEach(pending -> {
                if (!pending.writeInFlight && !now.isBefore(pending.nextAttempt)) {
                    pending.writeInFlight = true;
                    denials.add(pending);
                }
            });
            for (Entry entry : entries.values()) {
                if (entry.restoring && !now.isBefore(entry.restoreUntil)) {
                    entry.restoring = false;
                    jobs.add(entry);
                } else if (!entry.busy && entry.activeLoads == 0 && entry.terminal == null && !now.isBefore(entry.due)) {
                    entry.busy = true;
                    jobs.add(entry);
                }
            }
        }
        markers.forEach((credential, marker) -> retryMarker(marker));
        denials.forEach(this::retryPendingDenial);
        jobs.forEach(this::submit);
    }

    private void expire(Instant now) {
        entries.values().removeIf(entry -> {
            if (entry.lastAccess.plus(settings.idleExpiry()).isAfter(now)) return false;
            entry.valid = false;
            return true;
        });
        revoked.values().removeIf(denial -> !denial.until.isAfter(now));
        pendingDenials.values().removeIf(pending -> !pending.marker.until.isAfter(now));
        invalidated.values().removeIf(marker -> !marker.until.isAfter(now));
        if (!now.isBefore(restoreDisabledUntil)) restoreDisabledUntil = Instant.MIN;
        if (revoked.size() < settings.maximumEntries() && pendingDenials.size() < settings.maximumEntries()) {
            revocationSaturated = false;
        }
    }
    private boolean restoreBlocked(String credential) {
        Marker marker = invalidated.get(credential);
        return marker != null && marker.blocksRestore;
    }
    private boolean active(Entry entry) {
        return !closed && entry.valid && entries.get(entry.key) == entry
                && !revoked.containsKey(entry.credential) && !pendingDenials.containsKey(entry.credential);
    }
    @Override public void close(boolean drainComplete) {
        boolean clean;
        synchronized (this) {
            clean = drainComplete && !closed && pendingDenials.isEmpty() && unsettled.isEmpty()
                    && invalidated.values().stream().noneMatch(marker -> marker.pending);
            closed = true;
            entries.values().forEach(entry -> entry.valid = false);
            entries.clear();
            revoked.clear();
            pendingDenials.clear();
            invalidated.clear();
            unsettled.clear();
        }
        if (clean && barrier != null) {
            try { barrier.markClean(instanceId); }
            catch (RuntimeException ignored) { /* Leaving the checkpoint dirty is always safe. */ }
        }
    }

    private Map<String, Object> view(Entry entry) {
        String status = entry.body == null ? entry.error == null ? "preparing" : "unavailable"
                : !entry.forcedStale && entry.error == null && clock.instant().isBefore(entry.policy.nextRefresh(entry.updated)) ? "fresh" : "stale";
        Map<String, Object> body = entry.body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entry.body);
        if (entry.body == null) {
            body.put("status", status);
            body.put("message", status.equals("preparing") ? "数据准备中" : "数据暂不可用，请稍后重试");
        }
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("status", status);
        cache.put("refreshing", entry.busy);
        cache.put("updatedAt", entry.updated == null ? null : entry.updated.toString());
        cache.put("retryAfterMs", 1000);
        if (entry.error != null) cache.put("lastError", entry.error);
        body.put("cache", cache);
        return body;
    }
    private static Map<String, Object> unavailable(String code, String message) {
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("status", "unavailable"); cache.put("refreshing", false); cache.put("updatedAt", null);
        cache.put("retryAfterMs", 1000); cache.put("lastError", Map.of("code", code, "message", message));
        return Map.of("status", "unavailable", "message", message, "cache", cache);
    }
    private static BusinessException businessFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof BusinessException business) return business;
        }
        return null;
    }
    private String key(Identity identity, String realm, Map<String, ?> query) {
        try {
            return "readmodel:v2:" + digest(mapper.writeValueAsString(List.of(identity.tenantCode(), identity.authorization(),
                    identity.source(), identity.ownerKey(), realm, canonical(query))));
        } catch (Exception failure) { throw new IllegalArgumentException("Unsupported snapshot query", failure); }
    }
    private static String credential(Identity identity) { return ReadSnapshots.credentialHash(identity.tenantCode(), identity.authorization()); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> values) return values.stream().map(SnapshotReadModel::canonical).toList();
        return value;
    }
    private static Map<String, Object> cleanMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            String name = String.valueOf(key);
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!lower.contains("token") && !lower.equals("authorization") && !lower.contains("password") && !lower.equals("credential") && !name.equals("cache")) {
                result.put(name, clean(value));
            }
        });
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Object clean(Object value) {
        if (value instanceof Map<?, ?> map) return cleanMap(map);
        if (value instanceof Collection<?> values) return values.stream().map(SnapshotReadModel::clean).toList();
        return value;
    }
    private static final class Entry {
        final String key;
        final String credential;
        final String tenantCode;
        PolicyEnum policy;
        Supplier<Map<String, Object>> loader;
        Instant lastAccess;
        Instant updated;
        Instant due = Instant.MIN;
        Map<String, Object> body;
        Map<String, String> error;
        BusinessException terminal;
        boolean busy;
        boolean forcedStale;
        boolean restoring;
        Instant restoreUntil;
        boolean valid = true;
        int activeLoads;
        final Object loadLock = new Object();
        Entry(String key, String credential, String tenantCode, PolicyEnum policy,
                Supplier<Map<String, Object>> loader, Instant now) {
            this.key = key; this.credential = credential; this.tenantCode = tenantCode;
            this.policy = policy; this.loader = loader; this.lastAccess = now;
        }
    }
    private record Restored(Map<String, Object> body, Instant updated) { }
    private record Revocation(BusinessException failure, Instant until) { }
    private static final class Marker {
        final String credential;
        final String tenantCode;
        final String generation;
        final Instant issuedAt;
        Instant until;
        final int denialStatus;
        // Explicit invalidations and denials block restore until the credential is refreshed.
        boolean blocksRestore;
        boolean pending = true;
        Instant nextAttempt = Instant.MIN;
        Marker(String credential, String tenantCode, String generation, Instant issuedAt, Instant until,
                int denialStatus, boolean blocksRestore) {
            this.credential = credential; this.tenantCode = tenantCode; this.generation = generation;
            this.issuedAt = issuedAt; this.until = until; this.denialStatus = denialStatus;
            this.blocksRestore = blocksRestore;
        }
        void extend(Instant newUntil) {
            if (newUntil.isAfter(until)) until = newUntil;
        }
    }
    private static final class PendingDenial {
        final BusinessException rejection;
        final Marker marker;
        final BusinessException failure = new BusinessException(
                "SNAPSHOT_CREDENTIAL_PENDING", "凭据状态确认中，请稍后重试", 503);
        Instant nextAttempt;
        boolean writeInFlight;
        PendingDenial(BusinessException rejection, Marker marker, Instant nextAttempt) {
            this.rejection = rejection; this.marker = marker; this.nextAttempt = nextAttempt;
        }
    }
}
