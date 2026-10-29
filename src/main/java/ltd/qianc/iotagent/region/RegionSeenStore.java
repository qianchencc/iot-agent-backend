package ltd.qianc.iotagent.region;

import java.time.Instant;
import java.util.Optional;

/** Durable per-principal region acknowledgement facts. */
public interface RegionSeenStore {
    void markSeen(String tenantCode, String ownerKey, String regionId, Instant at);

    Optional<Instant> seenAt(String tenantCode, String ownerKey, String regionId);
}
