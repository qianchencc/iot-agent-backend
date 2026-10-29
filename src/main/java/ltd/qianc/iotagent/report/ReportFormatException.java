package ltd.qianc.iotagent.report;

import ltd.qianc.iotagent.error.BusinessException;

public final class ReportFormatException extends BusinessException {
    public ReportFormatException() {
        super("REPORT_FORMAT_UNSUPPORTED", "不支持的导出格式，请选择 PDF 或表格");
    }
}
