package ltd.qianc.iotagent.task.internal;

import java.time.Instant;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskSafetyLedgerReader;

/** Honest failure when no composed observation boundary is wired: no alarm history is ever invented. */
final class FailClosedSafetyLedgerReader implements TaskSafetyLedgerReader {
    @Override
    public Page read(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor) {
        throw new BusinessException("SAFETY_LEDGER_UNAVAILABLE", "安全台账读取未配置", 503);
    }
}
