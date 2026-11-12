package com.ehe.iotagent.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Consumer;
import com.ehe.iotagent.auth.internal.CachedAccountPrincipalVerifier;
import com.ehe.iotagent.auth.internal.WebClientAccountPrincipalVerifier;
import org.springframework.web.reactive.function.client.WebClient;

/** Verifies the existing customer login token and returns the stable upstream principal. */
public interface AccountPrincipalVerifier extends AutoCloseable {
    PrincipalInfo verify(PrincipalInfo presented);

    /** Production gateway authentication, with a bounded nonblocking verified-identity cache. */
    static AccountPrincipalVerifier gateway(WebClient client, String baseUrl, Duration timeout) {
        return gateway(client, baseUrl, AccountIdentityCachePolicy.defaults(timeout), Clock.systemUTC());
    }

    /** Clock is explicit so expiration and failure boundaries can be exercised through this public seam. */
    static AccountPrincipalVerifier gateway(
            WebClient client, String baseUrl, AccountIdentityCachePolicy policy, Clock clock) {
        return gateway(client, baseUrl, policy, clock, credential -> { });
    }

    /** Called on the managed worker after explicit rejection, before the credential can regain admission. */
    static AccountPrincipalVerifier gateway(
            WebClient client, String baseUrl, AccountIdentityCachePolicy policy, Clock clock,
            Consumer<PrincipalInfo> onDenied) {
        return gateway(client, baseUrl, policy, clock, onDenied, credential -> { });
    }

    /**
     * Called on the managed worker after every genuine verification. It must only clear an existing
     * denial (a no-op otherwise). A failure keeps admission closed so the barrier update is retried.
     */
    static AccountPrincipalVerifier gateway(
            WebClient client, String baseUrl, AccountIdentityCachePolicy policy, Clock clock,
            Consumer<PrincipalInfo> onDenied, Consumer<PrincipalInfo> onVerified) {
        return new CachedAccountPrincipalVerifier(
                new WebClientAccountPrincipalVerifier(client, baseUrl, policy.timeout()),
                baseUrl, policy, clock, onDenied, onVerified);
    }

    /** Restart-safe verified identities; persistence and revocation barriers remain backend-owned. */
    static AccountPrincipalVerifier gateway(
            WebClient client, String baseUrl, AccountIdentityCachePolicy policy, Clock clock,
            Consumer<PrincipalInfo> onDenied, Consumer<PrincipalInfo> onVerified,
            com.ehe.iotagent.readmodel.ReadSnapshots.Persistence persistence,
            com.ehe.iotagent.readmodel.ReadSnapshots.Barrier barrier,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new CachedAccountPrincipalVerifier(
                new WebClientAccountPrincipalVerifier(client, baseUrl, policy.timeout()),
                baseUrl, policy, clock, onDenied, onVerified,
                persistence == null || barrier == null ? null
                        : new com.ehe.iotagent.auth.internal.PersistedAccountIdentity(
                                persistence, barrier, mapper, policy.maxValidationAge()));
    }

    @Override
    default void close() { }
}
