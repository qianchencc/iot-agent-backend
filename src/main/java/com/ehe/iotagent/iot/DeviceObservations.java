package com.ehe.iotagent.iot;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.ehe.iotagent.iot.internal.JdbcDeviceObservations;
import com.ehe.iotagent.iot.internal.LocalDataKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Persisted current observations under the viewer's current local directory authorization. */
public interface DeviceObservations {
    /** Never collects source data. Missing sampling time remains unknown; stale values are not control evidence. */
    DeviceStateReadResult read(RequestIdentity identity, List<String> deviceIds);

    static DeviceObservations jdbc(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            DeviceDirectory directory, String gatewayInstance, Clock clock) {
        String gateway = LocalDataKeys.hash(gatewayInstance);
        return new JdbcDeviceObservations(jdbc, transactions, mapper, directory::loadBatch,
                identity -> LocalDataKeys.directory(gateway, identity), clock);
    }
}
