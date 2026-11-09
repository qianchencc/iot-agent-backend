package ltd.qianc.iotagent.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.iot.internal.JdbcCompanyDirectory;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Java-owned principal-isolated directory. Reads never collect customer data; synchronization is explicit. */
public interface DeviceDirectory extends AutoCloseable {
    /** Registers verified credentials and reads the last complete, currently authorized local batch. */
    Map<String, Object> read(RequestIdentity identity);

    /** Full server-side catalog, including bindings and paths; never serialize as an HTTP response. */
    default AuthorizedDeviceScope loadScope(RequestIdentity identity) { return loadBatch(identity).scope(); }

    /** Regions and devices from one published batch; never assemble them from separate source requests. */
    DeviceDirectoryBatch loadBatch(RequestIdentity identity);

    /** Runs due registered work; independent of page activity and projection-cache lifetime. */
    void synchronizeDue();

    /** Drives due full historical scans; the production cadence runs this independently of current data. */
    void synchronizeHistoryDue();

    /** Persisted raw energy facts and dedicated energy authorization. */
    EnergyHistory energyHistory();

    /** Local current environment observations and independently covered site history. */
    EnvironmentHistory environmentHistory();

    /** Starts the process-owned cadence. Tests and operational smoke runs may drive synchronizeDue directly. */
    void start();

    /** Own-scope operational metadata only; never returns credentials, hashes, or control bindings. */
    Map<String, Object> status(RequestIdentity identity);

    @Override void close();

    static DeviceDirectory jdbc(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            IotAdapter upstream, ObservationAdapter historySource, AccountPrincipalVerifier verifier, ReadSnapshots snapshots,
            ReadSnapshots.Barrier barrier, String gatewayInstance, String credentialKey, Clock clock) {
        return new JdbcCompanyDirectory(jdbc, transactions, mapper, upstream, () -> historySource, verifier, snapshots,
                barrier, gatewayInstance, credentialKey, clock);
    }
}
