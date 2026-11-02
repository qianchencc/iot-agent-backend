package ltd.qianc.iotagent.observation.internal.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded background preparation per identity, sharing the ordinary month results. */
final class CompanyEnergyHistoryPreparation {
    static final String DAILY_REALM = "COMPANY_ENERGY_DAILY:v3";
    private static final int MONTHS = 24;
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Logger LOG = LoggerFactory.getLogger(CompanyEnergyHistoryPreparation.class);
    private final ObservationAdapter adapter;
    private final ReadSnapshots snapshots;
    private final Clock clock;
    private final Duration timeout;

    CompanyEnergyHistoryPreparation(ObservationAdapter adapter, ReadSnapshots snapshots, Clock clock, Duration timeout) {
        this.adapter = adapter;
        this.snapshots = snapshots;
        this.clock = clock;
        this.timeout = timeout;
    }

    void observe(RequestIdentity identity) {
        YearMonth through = YearMonth.now(clock.withZone(SHANGHAI)).minusMonths(1);
        ReadSnapshots.Identity scope = new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(),
                identity.source().name(), identity.ownerKey());
        try {
            snapshots.read(scope, "ENERGY_HISTORY_PREPARATION", Map.of("through", through.toString(), "months", MONTHS),
                    ReadSnapshots.Policy.HISTORICAL, () -> prepare(identity, scope, through));
        } catch (BusinessException failure) {
            // Preparation must not displace a held foreground result when the cache is full.
            if (!"SNAPSHOT_CAPACITY".equals(failure.code())) throw failure;
            LOG.warn("Energy history preparation deferred: snapshot capacity reached");
        }
    }

    private Map<String, Object> prepare(RequestIdentity identity, ReadSnapshots.Identity scope, YearMonth through) {
        try {
            // A single existing snapshot worker advances from near to far. Inner loads run
            // inline on that worker, leaving the remaining workers available to foreground reads.
            for (int index = 0; index < MONTHS; index++) {
                YearMonth month = through.minusMonths(index);
                Map<String, String> query = Map.of("days", Integer.toString(month.lengthOfMonth()),
                        "end", month.atEndOfMonth().toString());
                snapshots.load(scope, DAILY_REALM, query, ReadSnapshots.Policy.HISTORICAL,
                        () -> monthResult(identity, query, month.lengthOfMonth()));
            }
            LOG.info("Energy history preparation complete: months={} through={}", MONTHS, through);
            return Map.of("months", MONTHS, "from", through.minusMonths(MONTHS - 1).toString(),
                    "through", through.toString());
        } catch (RuntimeException failure) {
            LOG.warn("Energy history preparation incomplete: through={} failure={}",
                    through, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private Map<String, Object> monthResult(RequestIdentity identity, Map<String, String> query, int days) {
        ObservationResponse response = adapter.read(new ObservationRequest(identity,
                ObservationOperation.COMPANY_ENERGY_DAILY, query)).block(timeout);
        if (response == null || response.status() != ObservationStatus.AVAILABLE
                || !response.coverageComplete() || !response.failures().isEmpty()
                || Boolean.TRUE.equals(response.payload().get("truncated"))
                || !(response.payload().get("days") instanceof List<?> rows) || rows.size() != days) {
            throw new ObservationUnavailableException("历史能耗后台准备未取得完整月份");
        }
        return new LinkedHashMap<>(response.payload());
    }
}
