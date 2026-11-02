package ltd.qianc.iotagent.auth.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;

/** Rebuildable verified metadata only. Raw credentials never enter persisted keys or values. */
public final class PersistedAccountIdentity {
    public record Held(String principalId, String identityRealm, Instant validatedAt) { }
    private final ReadSnapshots.Persistence persistence;
    private final ReadSnapshots.Barrier barrier;
    private final ObjectMapper mapper;
    private final Duration maximumAge;

    public PersistedAccountIdentity(ReadSnapshots.Persistence persistence, ReadSnapshots.Barrier barrier,
            ObjectMapper mapper, Duration maximumAge) {
        this.persistence = persistence;
        this.barrier = barrier;
        this.mapper = mapper;
        this.maximumAge = maximumAge;
    }

    public Optional<Held> read(String key, PrincipalInfo credential, Instant now) {
        try {
            String stored = persistence.get("account-identity:v1:" + key).orElse(null);
            if (stored == null) return Optional.empty();
            var body = mapper.readTree(stored);
            String principal = body.path("principalId").asText("");
            String realm = body.path("identityRealm").asText("");
            Instant at = Instant.parse(body.path("validatedAt").asText());
            if (principal.isBlank() || realm.isBlank() || at.isAfter(now) || !at.plus(maximumAge).isAfter(now))
                return Optional.empty();
            var state = barrier.read(barrierKey(credential)).orElse(null);
            if (state == null || state.denialStatus() != 0 || !state.until().isAfter(now)
                    || !state.generation().equals(body.path("generation").asText())) return Optional.empty();
            return Optional.of(new Held(principal, realm, at));
        } catch (Exception unavailableOrMalformed) {
            return Optional.empty();
        }
    }

    public void save(String key, PrincipalInfo verified, Instant at) {
        var barrierKey = barrierKey(verified);
        var state = barrier.read(barrierKey).filter(value -> value.until().isAfter(at)).orElse(null);
        if (state == null) {
            state = new ReadSnapshots.Barrier.State(UUID.randomUUID().toString(), at.plus(maximumAge), 0, at);
            if (!barrier.writeIfCurrent(barrierKey, null, state)) return;
        }
        if (state.denialStatus() != 0) return;
        try {
            persistence.put("account-identity:v1:" + key, mapper.writeValueAsString(Map.of(
                    "principalId", verified.principalId(), "identityRealm", verified.identityRealm(),
                    "validatedAt", at.toString(), "generation", state.generation())), maximumAge);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode verified identity metadata", failure);
        }
    }

    private static ReadSnapshots.Barrier.Key barrierKey(PrincipalInfo credential) {
        return new ReadSnapshots.Barrier.Key(credential.tenantCode(),
                ReadSnapshots.credentialHash(credential.tenantCode(), credential.authorization()));
    }
}
