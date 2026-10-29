package ltd.qianc.iotagent.region.internal;

import java.time.Instant;
import java.util.Optional;
import ltd.qianc.iotagent.region.RegionSeenStore;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL-backed region acknowledgements; Redis and process memory are not fact stores. */
public final class JdbcRegionSeenStore implements RegionSeenStore {
    private final JdbcTemplate jdbc;

    public JdbcRegionSeenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void markSeen(String tenantCode, String ownerKey, String regionId, Instant at) {
        jdbc.update("INSERT INTO region_seen(tenant_code,owner_key,region_id,seen_at,create_time,update_time) VALUES (?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE seen_at=GREATEST(seen_at,VALUES(seen_at)),update_time=VALUES(update_time)",
                tenantCode, ownerKey, regionId, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at), java.sql.Timestamp.from(at));
    }

    @Override
    public Optional<Instant> seenAt(String tenantCode, String ownerKey, String regionId) {
        return jdbc.query("SELECT seen_at FROM region_seen WHERE tenant_code=? AND owner_key=? AND region_id=?",
                        (rs, row) -> rs.getTimestamp(1).toInstant(), tenantCode, ownerKey, regionId)
                .stream().findFirst();
    }
}
