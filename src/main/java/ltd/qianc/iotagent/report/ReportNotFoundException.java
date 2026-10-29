package ltd.qianc.iotagent.report;

import ltd.qianc.iotagent.error.BusinessException;

/** Deliberately indistinguishable missing and unauthorized report facts. */
public final class ReportNotFoundException extends BusinessException {
    public ReportNotFoundException() {
        super("REPORT_NOT_FOUND", "报告不存在或没有查看权限", 404);
    }
}
