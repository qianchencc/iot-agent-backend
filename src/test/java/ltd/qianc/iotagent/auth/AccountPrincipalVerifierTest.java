package ltd.qianc.iotagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class AccountPrincipalVerifierTest {
    private static final PrincipalInfo CREDENTIAL = new PrincipalInfo("Bearer fixture-a", "tenant-a");
    private static final AccountIdentityCachePolicy POLICY = new AccountIdentityCachePolicy(
            Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(120), Duration.ofSeconds(60));

    @Test
    void simultaneousUnseenCredentialRequestsNeverWaitOrDuplicateVerification() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        Sinks.One<ClientResponse> response = Sinks.one();
        WebClient client = client(request -> Mono.defer(() -> {
            calls.incrementAndGet();
            started.countDown();
            return response.asMono();
        }));
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock())) {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<String>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                CompletableFuture<String> result = new CompletableFuture<>();
                results.add(result);
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        result.complete(failure(verifier, CREDENTIAL).code());
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
            }
            start.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                for (CompletableFuture<String> result : results) {
                    assertThat(result.get()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
                }
            });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            response.tryEmitValue(success("verified-account"));
            assertThat(awaitPrincipal(verifier, CREDENTIAL).principalId()).isEqualTo("verified-account");
        }
    }

    @Test
    void reusesOnlyActuallyVerifiedIdentityWithoutTrustingAssertedPrincipal() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = client(request -> {
            calls.incrementAndGet();
            assertThat(request.url().getPath()).isEqualTo("/sso-api/account/me");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo(CREDENTIAL.authorization());
            assertThat(request.headers().getFirst("Tenantcode")).isEqualTo(CREDENTIAL.tenantCode());
            return Mono.just(success("real-account"));
        });
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock())) {
            PrincipalInfo asserted = new PrincipalInfo(CREDENTIAL.authorization(), CREDENTIAL.tenantCode(), "forged", "https://forged.test");
            assertThat(failure(verifier, asserted).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
            PrincipalInfo verified = awaitPrincipal(verifier, asserted);
            assertThat(verified.principalId()).isEqualTo("real-account");
            assertThat(verified.identityRealm()).isEqualTo("https://realm-a.test");
            for (int index = 0; index < 20; index++) assertThat(verifier.verify(asserted)).isEqualTo(verified);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test
    void credentialTenantAndGatewayRealmRemainIsolated() {
        AtomicInteger calls = new AtomicInteger();
        WebClient client = client(request -> {
            calls.incrementAndGet();
            String identity = request.url().getHost() + ":" + request.headers().getFirst("Tenantcode")
                    + ":" + request.headers().getFirst("Authorization").endsWith("fixture-b");
            return Mono.just(success(identity));
        });
        try (AccountPrincipalVerifier first = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock());
                AccountPrincipalVerifier second = AccountPrincipalVerifier.gateway(client, "https://realm-b.test", POLICY, new MutableClock())) {
            PrincipalInfo rotated = new PrincipalInfo("Bearer fixture-b", "tenant-a");
            PrincipalInfo otherTenant = new PrincipalInfo(CREDENTIAL.authorization(), "tenant-b");
            PrincipalInfo initial = awaitPrincipal(first, CREDENTIAL);
            PrincipalInfo nextCredential = awaitPrincipal(first, rotated);
            PrincipalInfo nextTenant = awaitPrincipal(first, otherTenant);
            PrincipalInfo nextRealm = awaitPrincipal(second, CREDENTIAL);
            assertThat(nextCredential.principalId()).isNotEqualTo(initial.principalId());
            assertThat(nextTenant.principalId()).isNotEqualTo(initial.principalId());
            assertThat(nextRealm.principalId()).isNotEqualTo(initial.principalId());
            assertThat(nextRealm.identityRealm()).isEqualTo("https://realm-b.test");
            assertThat(first.verify(CREDENTIAL)).isEqualTo(initial);
            assertThat(calls.get()).isEqualTo(4);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void explicitRejectionEvictsHeldIdentityAndDeniesUntilSuccessfulReverification(int status) {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        Sinks.One<ClientResponse> rejection = Sinks.one();
        WebClient client = client(request -> calls.incrementAndGet() == 1
                ? Mono.just(success("account")) : rejection.asMono());
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, clock)) {
            awaitPrincipal(verifier, CREDENTIAL);
            clock.advance(Duration.ofSeconds(60));
            assertThat(verifier.verify(CREDENTIAL).principalId()).isEqualTo("account");
            rejection.tryEmitValue(ClientResponse.create(HttpStatus.valueOf(status)).build());
            BusinessException denied = awaitFailure(verifier, CREDENTIAL, "UNAUTHORIZED");
            assertThat(denied.httpStatus()).isEqualTo(401);
            for (int index = 0; index < 20; index++) assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("UNAUTHORIZED");
            assertThat(calls.get()).isEqualTo(2);
        }
    }

    @Test
    void bodyLevelRejectionNeverBecomesAVerifiedPrincipal() {
        WebClient client = client(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body("{\"code\":403,\"content\":{\"id\":\"untrusted\"}}").build()));
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock())) {
            assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
            assertThat(awaitFailure(verifier, CREDENTIAL, "UNAUTHORIZED").httpStatus()).isEqualTo(401);
        }
    }

    @Test
    void outageKeepsHeldIdentityOnlyUntilAbsoluteValidationAgeAndRespectsRetryCooldown() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        MutableClock clock = new MutableClock();
        WebClient client = client(request -> {
            if (calls.incrementAndGet() == 1) return Mono.just(success("account"));
            refreshStarted.countDown();
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
        });
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, clock)) {
            awaitPrincipal(verifier, CREDENTIAL);
            clock.advance(Duration.ofSeconds(60));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThat(verifier.verify(CREDENTIAL).principalId()).isEqualTo("account"));
            assertThat(refreshStarted.await(1, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 100; index++) assertThat(verifier.verify(CREDENTIAL).principalId()).isEqualTo("account");
            assertThat(calls.get()).isEqualTo(2);
            clock.advance(Duration.ofSeconds(60));
            assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_UNAVAILABLE");
            assertThat(failure(verifier, CREDENTIAL).httpStatus()).isEqualTo(503);
        }
    }

    @Test
    void unseenTransientFailureIsUnavailableNotAnUnverifiedIdentity() {
        WebClient client = client(request -> Mono.error(new IllegalStateException("fixture failure")));
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock())) {
            assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
            assertThat(awaitFailure(verifier, CREDENTIAL, "ACCOUNT_IDENTITY_UNAVAILABLE").httpStatus()).isEqualTo(503);
        }
    }

    @Test
    void closingCancelsOutstandingVerificationAndRejectsFurtherAdmission() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        WebClient client = client(request -> Mono.defer(() -> {
            started.countDown();
            return Mono.<ClientResponse>never().doOnCancel(cancelled::countDown);
        }));
        AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, new MutableClock());
        try {
            assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            verifier.close();
        }
        assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_UNAVAILABLE");
    }

    @Test
    void periodicVerificationClearsADataPlaneDenialAndNeverRestoresTheOldBody() {
        MutableClock clock = new MutableClock();
        ConcurrentLinkedQueue<Runnable> snapshotJobs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Runnable> storageJobs = new ConcurrentLinkedQueue<>();
        MemoryBarrier barrier = new MemoryBarrier(clock);
        MemoryPersistence persistence = new MemoryPersistence(clock);
        WebClient client = client(request -> Mono.just(success("account")));
        try (ReadSnapshots snapshots = ReadSnapshots.create(clock, snapshotJobs::add, storageJobs::add,
                persistence, barrier, new ObjectMapper(), ReadSnapshots.Settings.defaults());
                AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test",
                        POLICY, clock, credential -> snapshots.discard(identity(credential)),
                        credential -> snapshots.admit(identity(credential)))) {
            awaitPrincipal(verifier, CREDENTIAL);
            ReadSnapshots.Identity company = identity(CREDENTIAL);
            snapshots.read(company, "realm", Map.of(), ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "old"));
            drainSnapshotWork(snapshotJobs, storageJobs);
            assertThat(snapshots.read(company, "realm", Map.of(), ReadSnapshots.Policy.CURRENT,
                    () -> Map.of("value", "old"))).containsEntry("value", "old");

            snapshots.revoke(company, new BusinessException("DENIED", "无权读取", 403));
            assertThrows(BusinessException.class, () -> snapshots.read(company, "realm", Map.of(),
                    ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "old")));

            // The account entry is still verified; only its periodic refresh can clear the data-plane denial.
            clock.advance(Duration.ofSeconds(60));
            verifier.verify(CREDENTIAL);
            Map<String, Object> cold = awaitDenialCleared(snapshots, company);
            assertThat(cold).containsEntry("status", "preparing").doesNotContainKey("value");
            drainSnapshotWork(snapshotJobs, storageJobs);
            assertThat(snapshots.read(company, "realm", Map.of(), ReadSnapshots.Policy.CURRENT,
                    () -> Map.of("value", "new"))).containsEntry("value", "new");
        }
    }

    @Test
    void rejectionIsNotPublishedUntilItsDurableInvalidationSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        MutableClock clock = new MutableClock();
        WebClient client = client(request -> calls.incrementAndGet() >= 2
                ? Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build())
                : Mono.just(success("account")));
        try (AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test",
                POLICY, clock, credential -> {
                    if (invalidations.incrementAndGet() == 1) throw new IllegalStateException("barrier down");
                }, credential -> { })) {
            awaitPrincipal(verifier, CREDENTIAL);
            clock.advance(Duration.ofSeconds(60));
            verifier.verify(CREDENTIAL);
            BusinessException notPublished = awaitFailure(verifier, CREDENTIAL, "ACCOUNT_IDENTITY_UNAVAILABLE");
            assertThat(notPublished.httpStatus()).isEqualTo(503);
            assertThat(invalidations.get()).isEqualTo(1);
            clock.advance(Duration.ofSeconds(60));
            BusinessException denied = awaitFailure(verifier, CREDENTIAL, "UNAUTHORIZED");
            assertThat(denied.httpStatus()).isEqualTo(401);
            assertThat(invalidations.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void rejectedCredentialMustReacquireAllSnapshotsAfterGenuineReadmission() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock();
        CountDownLatch discarded = new CountDownLatch(1);
        ConcurrentLinkedQueue<Runnable> snapshotJobs = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Runnable> storageJobs = new ConcurrentLinkedQueue<>();
        WebClient client = client(request -> calls.incrementAndGet() == 2
                ? Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build())
                : Mono.just(success("account")));
        try (ReadSnapshots snapshots = ReadSnapshots.create(clock, snapshotJobs::add, storageJobs::add,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
                AccountPrincipalVerifier verifier = AccountPrincipalVerifier.gateway(client, "https://realm-a.test", POLICY, clock,
                        credential -> {
                            snapshots.discard(new ReadSnapshots.Identity(credential.tenantCode(), credential.authorization(), "company", ""));
                            discarded.countDown();
                        },
                        credential -> snapshots.admit(new ReadSnapshots.Identity(
                                credential.tenantCode(), credential.authorization(), "company", "")))) {
            awaitPrincipal(verifier, CREDENTIAL);
            ReadSnapshots.Identity company = new ReadSnapshots.Identity(CREDENTIAL.tenantCode(), CREDENTIAL.authorization(), "company", "old-owner");
            ReadSnapshots.Identity office = new ReadSnapshots.Identity(CREDENTIAL.tenantCode(), CREDENTIAL.authorization(), "office", "different-owner");
            for (ReadSnapshots.Identity identity : List.of(company, office)) {
                snapshots.read(identity, "realm", Map.of(), ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "old"));
                snapshotJobs.remove().run();
                assertThat(snapshots.read(identity, "realm", Map.of(), ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "old")))
                        .containsEntry("value", "old");
            }
            clock.advance(Duration.ofSeconds(60));
            verifier.verify(CREDENTIAL);
            assertThat(discarded.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure(verifier, CREDENTIAL).code()).isEqualTo("UNAUTHORIZED");
            clock.advance(Duration.ofSeconds(60));
            PrincipalInfo readmitted = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        return verifier.verify(CREDENTIAL);
                    } catch (BusinessException denied) {
                        assertThat(denied.code()).isEqualTo("UNAUTHORIZED");
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                }
                throw new AssertionError("Credential was not readmitted after genuine verification");
            });
            assertThat(readmitted.principalId()).isEqualTo("account");
            assertThat(calls.get()).isEqualTo(3);
            for (ReadSnapshots.Identity identity : List.of(company, office)) {
                Map<String, Object> cold = snapshots.read(identity, "realm", Map.of(), ReadSnapshots.Policy.CURRENT,
                        () -> Map.of("value", "new"));
                assertThat(cold).containsEntry("status", "preparing").doesNotContainKey("value");
                snapshotJobs.remove().run();
                assertThat(snapshots.read(identity, "realm", Map.of(), ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "new")))
                        .containsEntry("value", "new");
            }
        }
    }

    @Test
    void restartRestoresVerifiedIdentityWithoutWaitingForGateway() {
        MutableClock clock = new MutableClock();
        MemoryBarrier barrier = new MemoryBarrier(clock);
        MemoryPersistence persistence = new MemoryPersistence(clock);
        try (AccountPrincipalVerifier first = AccountPrincipalVerifier.gateway(
                client(request -> Mono.just(success("account"))), "https://realm-a.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            assertThat(awaitPrincipal(first, CREDENTIAL).principalId()).isEqualTo("account");
        }
        try (AccountPrincipalVerifier restarted = AccountPrincipalVerifier.gateway(
                client(request -> Mono.never()), "https://realm-a.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            assertThat(awaitPrincipal(restarted, CREDENTIAL).principalId()).isEqualTo("account");
        }
    }

    @Test
    void persistedIdentityCannotReadmitARejectedCredential() throws Exception {
        MutableClock clock = new MutableClock();
        MemoryBarrier barrier = new MemoryBarrier(clock);
        MemoryPersistence persistence = new MemoryPersistence(clock);
        try (ReadSnapshots snapshots = ReadSnapshots.create(clock, Runnable::run, Runnable::run,
                persistence, barrier, new ObjectMapper(), ReadSnapshots.Settings.defaults())) {
            try (AccountPrincipalVerifier first = AccountPrincipalVerifier.gateway(
                    client(request -> Mono.just(success("account"))), "https://realm-a.test", POLICY, clock,
                    credential -> snapshots.discard(identity(credential)), credential -> snapshots.admit(identity(credential)),
                    persistence, barrier, new ObjectMapper())) {
                awaitPrincipal(first, CREDENTIAL);
            }
            snapshots.revoke(identity(CREDENTIAL), new BusinessException("DENIED", "denied", 403));
            CountDownLatch genuineVerification = new CountDownLatch(1);
            try (AccountPrincipalVerifier restarted = AccountPrincipalVerifier.gateway(
                    client(request -> { genuineVerification.countDown(); return Mono.never(); }),
                    "https://realm-a.test", POLICY, clock, credential -> snapshots.discard(identity(credential)),
                    credential -> snapshots.admit(identity(credential)), persistence, barrier, new ObjectMapper())) {
                assertThat(failure(restarted, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
                assertThat(genuineVerification.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(failure(restarted, CREDENTIAL).code()).isEqualTo("ACCOUNT_IDENTITY_PREPARING");
                assertThrows(BusinessException.class, () -> snapshots.read(identity(CREDENTIAL),
                        "realm", Map.of(), ReadSnapshots.Policy.CURRENT, () -> Map.of("value", "forbidden")));
            }
        }
    }

    @Test
    void expiredPersistedIdentityMustBeGenuinelyVerifiedAgain() {
        MutableClock clock = new MutableClock();
        MemoryBarrier barrier = new MemoryBarrier(clock);
        MemoryPersistence persistence = new MemoryPersistence(clock);
        try (AccountPrincipalVerifier first = AccountPrincipalVerifier.gateway(
                client(request -> Mono.just(success("old-account"))), "https://realm-a.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            awaitPrincipal(first, CREDENTIAL);
        }
        clock.advance(POLICY.maxValidationAge());
        try (AccountPrincipalVerifier restarted = AccountPrincipalVerifier.gateway(
                client(request -> Mono.just(success("new-account"))), "https://realm-a.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            assertThat(awaitPrincipal(restarted, CREDENTIAL).principalId()).isEqualTo("new-account");
        }
    }

    @Test
    void persistedIdentityIsolatedByRealmTenantAndCredential() {
        MutableClock clock = new MutableClock();
        MemoryBarrier barrier = new MemoryBarrier(clock);
        MemoryPersistence persistence = new MemoryPersistence(clock);
        try (AccountPrincipalVerifier first = AccountPrincipalVerifier.gateway(
                client(request -> Mono.just(success("original"))), "https://realm-a.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            awaitPrincipal(first, CREDENTIAL);
        }
        for (PrincipalInfo changed : List.of(new PrincipalInfo("Bearer fixture-b", "tenant-a"),
                new PrincipalInfo(CREDENTIAL.authorization(), "tenant-b"))) {
            try (AccountPrincipalVerifier other = AccountPrincipalVerifier.gateway(
                    client(request -> Mono.just(success("different"))), "https://realm-a.test", POLICY, clock,
                    credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
                assertThat(awaitPrincipal(other, changed).principalId()).isEqualTo("different");
            }
        }
        try (AccountPrincipalVerifier otherRealm = AccountPrincipalVerifier.gateway(
                client(request -> Mono.just(success("different-realm"))), "https://realm-b.test", POLICY, clock,
                credential -> { }, credential -> { }, persistence, barrier, new ObjectMapper())) {
            assertThat(awaitPrincipal(otherRealm, CREDENTIAL).principalId()).isEqualTo("different-realm");
        }
    }

    private static ReadSnapshots.Identity identity(PrincipalInfo credential) {
        return new ReadSnapshots.Identity(credential.tenantCode(), credential.authorization(), "company", "");
    }

    private static void drainSnapshotWork(java.util.Queue<Runnable> upstream, java.util.Queue<Runnable> storage) {
        for (int pass = 0; pass < 100; pass++) {
            Runnable job;
            while ((job = storage.poll()) != null) job.run();
            while ((job = upstream.poll()) != null) job.run();
            if (storage.isEmpty() && upstream.isEmpty()) return;
        }
        throw new AssertionError("Snapshot work failed to settle");
    }

    private static Map<String, Object> awaitDenialCleared(ReadSnapshots snapshots, ReadSnapshots.Identity identity) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        BusinessException last = null;
        while (System.nanoTime() < deadline) {
            try {
                return snapshots.read(identity, "realm", Map.of(), ReadSnapshots.Policy.CURRENT,
                        () -> Map.of("value", "new"));
            } catch (BusinessException denied) {
                last = denied;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
        throw new AssertionError("Periodic verification did not clear the data-plane denial", last);
    }

    private static WebClient client(ExchangeFunction exchange) {
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static ClientResponse success(String principalId) {
        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                .body("{\"code\":0,\"content\":{\"id\":\"" + principalId + "\"}}").build();
    }

    private static BusinessException failure(AccountPrincipalVerifier verifier, PrincipalInfo principal) {
        return assertThrows(BusinessException.class, () -> verifier.verify(principal));
    }

    private static PrincipalInfo awaitPrincipal(AccountPrincipalVerifier verifier, PrincipalInfo principal) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                return verifier.verify(principal);
            } catch (BusinessException error) {
                if (!error.code().equals("ACCOUNT_IDENTITY_PREPARING")) throw error;
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        }
        throw new AssertionError("Background verification did not finish");
    }

    private static BusinessException awaitFailure(AccountPrincipalVerifier verifier, PrincipalInfo principal, String code) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try {
                verifier.verify(principal);
            } catch (BusinessException error) {
                if (error.code().equals(code)) return error;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("Background verification did not produce " + code);
    }

    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Clock clock;
        private final Map<Key, State> values = new LinkedHashMap<>();
        private boolean clean = true;
        private String epoch = java.util.UUID.randomUUID().toString();
        private String instance;
        MemoryBarrier(Clock clock) { this.clock = clock; }
        @Override public synchronized Checkpoint startInstance(String id) {
            boolean previous = clean;
            if (!previous) epoch = java.util.UUID.randomUUID().toString();
            instance = id;
            clean = false;
            return new Checkpoint(previous, epoch);
        }
        @Override public synchronized boolean markClean(String id) {
            if (!id.equals(instance)) return false;
            clean = true;
            return true;
        }
        @Override public synchronized Optional<State> read(Key key) {
            State state = values.get(key);
            return state == null || !state.until().isAfter(clock.instant()) ? Optional.empty() : Optional.of(state);
        }
        @Override public synchronized boolean write(Key key, State state) {
            State current = values.get(key);
            if (current != null && state.issuedAt().isBefore(current.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
        @Override public synchronized boolean writeIfCurrent(Key key, String expected, State state) {
            State current = values.get(key);
            if (expected == null) {
                if (current != null && current.until().isAfter(clock.instant())) return false;
            } else if (current == null || !expected.equals(current.generation())) {
                return false;
            }
            if (current != null && state.issuedAt().isBefore(current.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
    }

    private static final class MemoryPersistence implements ReadSnapshots.Persistence {
        private final Clock clock;
        private final Map<String, Stored> values = new LinkedHashMap<>();
        MemoryPersistence(Clock clock) { this.clock = clock; }
        @Override public synchronized Optional<String> get(String key) {
            Stored stored = values.get(key);
            return stored == null || !stored.until.isAfter(clock.instant()) ? Optional.empty() : Optional.of(stored.value);
        }
        @Override public synchronized void put(String key, String value, Duration retention) {
            values.put(key, new Stored(value, clock.instant().plus(retention)));
        }
        @Override public synchronized void delete(String key) { values.remove(key); }
        private record Stored(String value, Instant until) { }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T00:00:00Z"));

        void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
