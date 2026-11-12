package com.ehe.iotagent.task.internal;

import java.time.Instant;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.task.TaskSafetyLedgerReader;

/** Honest failure when no composed observation boundary is wired: no alarm history is ever invented. */
final class FailClosedSafetyLedgerReader implements TaskSafetyLedgerReader {
    @Override
    public Page read(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor) {
        throw new BusinessException("SAFETY_LEDGER_UNAVAILABLE", "安全台账读取未配置", 503);
    }
}
