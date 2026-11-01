package ltd.qianc.iotagent.storage.internal;

import java.sql.Timestamp;
import java.util.List;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL-backed credential generation barrier shared by every process using the same gateway.
 * Only the tenant, a credential hash and the generation metadata are stored; tokens never are.
 */
public final class JdbcCredentialBarrier implements ReadSnapshots.Barrier {
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final String gatewayNamespace;

    public JdbcCredentialBarrier(JdbcTemplate jdbc,
            org.springframework.transaction.support.TransactionTemplate transactions, String gatewayNamespace) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.gatewayNamespace = gatewayNamespace;
    }

    @Override
    public Checkpoint startInstance(String instanceId) {
        return transactions.execute(status -> {
            List<Checkpoint> previous = jdbc.query(
                    "SELECT clean, recovery_epoch FROM read_snapshot_recovery_checkpoints "
                            + "WHERE gateway_namespace=? FOR UPDATE",
                    (result, row) -> new Checkpoint(result.getInt(1) == 1, result.getString(2)),
                    gatewayNamespace);
            boolean recoverySafe = !previous.isEmpty() && previous.get(0).recoverySafe();
            String epoch = recoverySafe ? previous.get(0).epoch() : java.util.UUID.randomUUID().toString();
            Timestamp now = Timestamp.from(java.time.Instant.now());
            jdbc.update("INSERT INTO read_snapshot_recovery_checkpoints"
                            + "(gateway_namespace,instance_id,clean,recovery_epoch,update_time) VALUES (?,?,0,?,?) "
                            + "ON DUPLICATE KEY UPDATE instance_id=VALUES(instance_id),clean=0,"
                            + "recovery_epoch=VALUES(recovery_epoch),update_time=VALUES(update_time)",
                    gatewayNamespace, instanceId, epoch, now);
            return new Checkpoint(recoverySafe, epoch);
        });
    }

    @Override
    public boolean markClean(String instanceId) {
        return jdbc.update("UPDATE read_snapshot_recovery_checkpoints SET clean=1,update_time=? "
                        + "WHERE gateway_namespace=? AND instance_id=?",
                Timestamp.from(java.time.Instant.now()), gatewayNamespace, instanceId) > 0;
    }

    @Override
    public java.util.Optional<State> read(Key key) {
        List<State> rows = jdbc.query(
                "SELECT generation, active_until, denial_status, issued_at FROM read_snapshot_credential_barriers "
                        + "WHERE gateway_namespace=? AND tenant_code=? AND credential_hash=?",
                (result, row) -> new State(result.getString(1),
                        result.getTimestamp(2).toInstant(), result.getInt(3),
                        result.getTimestamp(4).toInstant()),
                gatewayNamespace, key.tenantCode(), key.credentialHash());
        return rows.stream().findFirst();
    }

    @Override
    public boolean write(Key key, State state) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        // A stale retry keeps its original issued_at and must not overwrite a later admission.
        int changed = jdbc.update("INSERT INTO read_snapshot_credential_barriers"
                        + "(gateway_namespace,tenant_code,credential_hash,generation,denial_status,active_until,issued_at,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "generation=IF(VALUES(issued_at)>=issued_at,VALUES(generation),generation),"
                        + "denial_status=IF(VALUES(issued_at)>=issued_at,VALUES(denial_status),denial_status),"
                        + "active_until=IF(VALUES(issued_at)>=issued_at,VALUES(active_until),active_until),"
                        + "update_time=IF(VALUES(issued_at)>=issued_at,VALUES(update_time),update_time),"
                        + "issued_at=IF(VALUES(issued_at)>=issued_at,VALUES(issued_at),issued_at)",
                gatewayNamespace, key.tenantCode(), key.credentialHash(), state.generation(),
                state.denialStatus(), Timestamp.from(state.until()), Timestamp.from(state.issuedAt()), now, now);
        if (changed > 0) return true;
        // MySQL reports no changed rows for an identical retry; that still means the state is durable.
        Integer stored = jdbc.queryForObject("SELECT COUNT(*) FROM read_snapshot_credential_barriers "
                        + "WHERE gateway_namespace=? AND tenant_code=? AND credential_hash=? "
                        + "AND generation=? AND denial_status=? AND issued_at=?",
                Integer.class, gatewayNamespace, key.tenantCode(), key.credentialHash(),
                state.generation(), state.denialStatus(), Timestamp.from(state.issuedAt()));
        return stored != null && stored > 0;
    }

    @Override
    public boolean writeIfCurrent(Key key, String expectedGeneration, State state) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        if (expectedGeneration == null) {
            // An expired row still occupies the primary key but no longer proves a current generation.
            int renewed = jdbc.update("UPDATE read_snapshot_credential_barriers SET generation=?,denial_status=?,"
                            + "active_until=?,issued_at=?,update_time=? WHERE gateway_namespace=? AND tenant_code=? "
                            + "AND credential_hash=? AND active_until<=? AND issued_at<=?",
                    state.generation(), state.denialStatus(), Timestamp.from(state.until()),
                    Timestamp.from(state.issuedAt()), now,
                    gatewayNamespace, key.tenantCode(), key.credentialHash(), now, Timestamp.from(state.issuedAt()));
            if (renewed == 1) return true;
            return jdbc.update("INSERT IGNORE INTO read_snapshot_credential_barriers"
                            + "(gateway_namespace,tenant_code,credential_hash,generation,denial_status,active_until,issued_at,create_time,update_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    gatewayNamespace, key.tenantCode(), key.credentialHash(), state.generation(),
                    state.denialStatus(), Timestamp.from(state.until()), Timestamp.from(state.issuedAt()), now, now) == 1;
        }
        return jdbc.update("UPDATE read_snapshot_credential_barriers SET generation=?,denial_status=?,"
                        + "active_until=?,issued_at=?,update_time=? WHERE gateway_namespace=? AND tenant_code=? "
                        + "AND credential_hash=? AND generation=? AND issued_at<=?",
                state.generation(), state.denialStatus(), Timestamp.from(state.until()),
                Timestamp.from(state.issuedAt()), now,
                gatewayNamespace, key.tenantCode(), key.credentialHash(), expectedGeneration,
                Timestamp.from(state.issuedAt())) == 1;
    }
}
