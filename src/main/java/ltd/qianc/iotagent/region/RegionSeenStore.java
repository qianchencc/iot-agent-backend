package ltd.qianc.iotagent.region;

import java.time.Instant;
import java.util.Map;

/** Durable per-principal region acknowledgement facts. */
public interface RegionSeenStore {
    void markSeen(String tenantCode, String ownerKey, String regionId, Instant at);

    /** Missing watermarks mean never viewed, not that an exceptional result exists. */
    Map<String, Instant> seenAtByRegion(String tenantCode, String ownerKey);
}
