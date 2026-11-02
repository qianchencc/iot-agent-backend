package ltd.qianc.iotagent.auth.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import ltd.qianc.iotagent.auth.AccountIdentityCachePolicy;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;

/** Only verified upstream identities are held; credentials are never persisted or used as map keys. */
public final class CachedAccountPrincipalVerifier implements AccountPrincipalVerifier {
    private static final int MAX_ENTRIES = 4096;
    private final AccountPrincipalVerifier upstream;
    private final String realm;
    private final AccountIdentityCachePolicy policy;
    private final Clock clock;
    private final Consumer<PrincipalInfo> onDenied;
    private final Consumer<PrincipalInfo> onVerified;
    private final ThreadPoolExecutor executor;
    private final PersistedAccountIdentity persistence;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private volatile boolean closed;

    public CachedAccountPrincipalVerifier(
            AccountPrincipalVerifier upstream, String realm, AccountIdentityCachePolicy policy, Clock clock,
            Consumer<PrincipalInfo> onDenied, Consumer<PrincipalInfo> onVerified) {
        this(upstream, realm, policy, clock, onDenied, onVerified, null);
    }

    public CachedAccountPrincipalVerifier(
            AccountPrincipalVerifier upstream, String realm, AccountIdentityCachePolicy policy, Clock clock,
            Consumer<PrincipalInfo> onDenied, Consumer<PrincipalInfo> onVerified, PersistedAccountIdentity persistence) {
        this.persistence = persistence;
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.realm = Objects.requireNonNull(realm, "realm");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onDenied = Objects.requireNonNull(onDenied, "onDenied");
        this.onVerified = Objects.requireNonNull(onVerified, "onVerified");
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> {
                    Thread thread = new Thread(runnable, "account-identity-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public PrincipalInfo verify(PrincipalInfo presented) {
        Objects.requireNonNull(presented, "presented");
        if (presented.authorization() == null || presented.authorization().isBlank()
                || presented.tenantCode() == null || presented.tenantCode().isBlank()) throw denied();
        if (closed) throw unavailable();
        String key = key(presented);
        synchronized (entries) {
            return readEntry(entry(key), presented);
        }
    }

    private PrincipalInfo readEntry(Entry entry, PrincipalInfo presented) {
        synchronized (entry) {
            Instant now = clock.instant();
            // Evaluate held state before dispatch: even an instantly completed cold check returns preparing.
            PrincipalInfo held = entry.principalId != null && now.isBefore(entry.validatedAt.plus(policy.maxValidationAge()))
                    ? new PrincipalInfo(presented.authorization(), presented.tenantCode(), entry.principalId, entry.identityRealm)
                    : null;
            boolean rejected = entry.denied;
            boolean failed = entry.failed || entry.validatedAt != null && held == null;
            if (!entry.inFlight && (entry.retryAt == null || !now.isBefore(entry.retryAt))) {
                entry.inFlight = true;
                // Drop caller-asserted principal fields. Only the actual gateway may establish identity.
                PrincipalInfo credential = new PrincipalInfo(presented.authorization(), presented.tenantCode());
                try {
                    executor.execute(() -> refresh(entry, credential));
                } catch (RejectedExecutionException saturated) {
                    entry.inFlight = false;
                    entry.failed = true;
                    entry.retryAt = now.plus(policy.retryCooldown());
                    failed = true;
                }
            }
            if (rejected) throw denied();
            if (held != null) return held;
            if (failed) throw unavailable();
            throw preparing();
        }
    }

    private Entry entry(String key) {
        synchronized (entries) {
            if (closed) throw unavailable();
            Entry entry = entries.get(key);
            if (entry != null) return entry;
            if (entries.size() >= MAX_ENTRIES) {
                var iterator = entries.entrySet().iterator();
                boolean removed = false;
                while (iterator.hasNext()) {
                    Entry candidate = iterator.next().getValue();
                    synchronized (candidate) {
                        if (!candidate.inFlight && !candidate.invalidationPending) {
                            iterator.remove();
                            removed = true;
                            break;
                        }
                    }
                }
                if (!removed) throw unavailable();
            }
            entry = new Entry();
            entries.put(key, entry);
            return entry;
        }
    }

    private void refresh(Entry entry, PrincipalInfo credential) {
        try {
            if (entry.invalidationPending && !invalidate(entry, credential)) throw unavailable();
            if (persistence != null && !entry.restoreAttempted) {
                entry.restoreAttempted = true;
                var held = persistence.read(key(credential), credential, clock.instant()).orElse(null);
                if (held != null) {
                    synchronized (entry) {
                        if (closed) return;
                        entry.principalId = held.principalId();
                        entry.identityRealm = held.identityRealm();
                        entry.validatedAt = held.validatedAt();
                        entry.retryAt = held.validatedAt().plus(policy.refreshInterval());
                    }
                    return;
                }
            }
            PrincipalInfo verified = upstream.verify(credential);
            if (verified == null || !verified.verified()
                    || !credential.authorization().equals(verified.authorization())
                    || !credential.tenantCode().equals(verified.tenantCode())) throw unavailable();
            try {
                // A read-projection denial can outlive the account cache entry (for example a data
                // API 403): every genuine verification must clear it. The callback itself is a no-op
                // when no denial exists, so routine 60s refreshes only perform one local barrier check.
                onVerified.accept(credential);
            } catch (RuntimeException notDurable) {
                // Stay out of admission until the old denial has actually been cleared durably.
                synchronized (entry) {
                    entry.failed = true;
                    entry.retryAt = clock.instant().plus(policy.retryCooldown());
                }
                return;
            }
            Instant validatedAt = clock.instant();
            if (persistence != null) {
                try { persistence.save(key(credential), verified, validatedAt); }
                catch (RuntimeException unavailable) { /* Genuine verification remains valid; restart will reverify. */ }
            }
            synchronized (entry) {
                if (closed) return;
                entry.principalId = verified.principalId();
                entry.identityRealm = verified.identityRealm();
                entry.validatedAt = validatedAt;
                entry.retryAt = entry.validatedAt.plus(policy.refreshInterval());
                entry.denied = false;
                entry.failed = false;
            }
        } catch (RuntimeException error) {
            boolean rejection = error instanceof BusinessException business
                    && (business.httpStatus() == 401 || business.httpStatus() == 403);
            synchronized (entry) {
                if (rejection) {
                    entry.principalId = null;
                    entry.identityRealm = null;
                }
                entry.failed = true;
                entry.retryAt = clock.instant().plus(policy.retryCooldown());
            }
            if (rejection) {
                // Never publish the rejection before its durable barrier is in place: a process that
                // observes 401 must be safe to kill without flushing any in-memory state.
                boolean durable = invalidate(entry, credential);
                synchronized (entry) {
                    entry.denied = durable;
                    entry.invalidationPending = !durable;
                }
            }
        } finally {
            synchronized (entry) {
                entry.inFlight = false;
            }
        }
    }

    private boolean invalidate(Entry entry, PrincipalInfo credential) {
        try {
            onDenied.accept(credential);
            synchronized (entry) {
                entry.invalidationPending = false;
            }
            return true;
        } catch (RuntimeException failure) {
            // Keep admission denied until rejected-credential projections have actually been discarded.
            return false;
        }
    }

    private String key(PrincipalInfo presented) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateKey(digest, realm);
            updateKey(digest, presented.tenantCode());
            updateKey(digest, presented.authorization());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void updateKey(MessageDigest digest, String part) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();

        synchronized (entries) {
            entries.clear();
        }
        upstream.close();
    }

    private static BusinessException preparing() {
        return new BusinessException("ACCOUNT_IDENTITY_PREPARING", "账户身份核验中，请稍后重试", "请稍后重试", 503);
    }

    private static BusinessException unavailable() {
        return new BusinessException("ACCOUNT_IDENTITY_UNAVAILABLE", "当前账户身份暂不可用，请稍后重试", "请稍后重试", 503);
    }

    private static BusinessException denied() {
        return new BusinessException("UNAUTHORIZED", "身份凭据无法核验", "请重新登录", 401);
    }

    private static final class Entry {
        private String principalId;
        private String identityRealm;
        private Instant validatedAt;
        private Instant retryAt;
        private boolean inFlight;
        private boolean denied;
        private boolean failed;
        private boolean invalidationPending;
        private boolean restoreAttempted;
    }
}
